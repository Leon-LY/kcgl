package com.kcgl.module.image;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kcgl.common.audit.AuditRecorder;
import com.kcgl.common.web.BizException;
import com.kcgl.common.web.ErrorCode;
import com.kcgl.module.image.dto.ImageResponse;
import com.kcgl.module.item.ItemEntity;
import com.kcgl.module.item.ItemMapper;
import net.coobird.thumbnailator.Thumbnails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * 图片上传管线（docs/01 7.5 唯一定义）：
 * 幂等读回（uk_client_uuid，重放 200 绝不 409）→ 商品存在且未冻结 → 单件 9 图上限 →
 * 尺寸上限 → magic bytes 白名单（JPEG/PNG）→ 读头像素上限（防像素炸弹，不整图解码）→
 * 全量解码后统一重编码（BufferedImage 无元数据=EXIF/GPS 必然剥离，防仓库/拍卖场坐标泄露）→
 * 256px 缩略图 → 事务内 INSERT + 审计。
 *
 * 文件布局：{dir}/orig/{yyyy}/{MM}/{uuid}.jpg 与 {dir}/thumb/…；uuid 复用 clientUuid
 * （已校验 UUID 格式且全局唯一，重放写同一路径=同内容幂等）。
 */
@Service
public class ImageService {

    private static final Logger log = LoggerFactory.getLogger(ImageService.class);
    static final int MAX_IMAGES_PER_ITEM = 9;
    private static final byte[] JPEG_MAGIC = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] PNG_MAGIC =
            {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, (byte) 0x0A};
    private static final DateTimeFormatter PATH_FORMAT = DateTimeFormatter.ofPattern("yyyy/MM");
    private static final double ORIG_QUALITY = 0.85;
    private static final int THUMB_EDGE = 256;
    private static final double THUMB_QUALITY = 0.7;

    private final ImageMapper imageMapper;
    private final ItemMapper itemMapper;
    private final AuditRecorder auditRecorder;
    private final ImageProperties properties;
    private final Clock clock;
    private final TransactionTemplate txTemplate;

    public ImageService(ImageMapper imageMapper, ItemMapper itemMapper, AuditRecorder auditRecorder,
            ImageProperties properties, Clock clock, TransactionTemplate txTemplate) {
        this.imageMapper = imageMapper;
        this.itemMapper = itemMapper;
        this.auditRecorder = auditRecorder;
        this.properties = properties;
        this.clock = clock;
        this.txTemplate = txTemplate;
    }

    public ImageResponse upload(MultipartFile file, String clientUuid, long itemId, int imageType) {
        String uuid = requireUuid(clientUuid);

        // 幂等读回（7.0）：同键重放返回已有记录——响应在网络切换中丢失后的重试必须 200 出清
        ImageEntity existing = imageMapper.selectOne(new LambdaQueryWrapper<ImageEntity>()
                .eq(ImageEntity::getClientUuid, uuid));
        if (existing != null) {
            return ImageResponse.from(existing);
        }

        requireUpdatableItem(itemId);
        requireImageSlot(itemId);
        byte[] data = readWithinLimit(file);
        requireSupportedFormat(data);
        BufferedImage image = decodeWithinPixelLimit(data);
        BufferedImage flattened = flattenOnWhite(image);

        String relative = LocalDateTime.now(clock).format(PATH_FORMAT) + "/" + uuid + ".jpg";
        Path origFile = properties.origRoot().resolve(relative);
        Path thumbFile = properties.thumbRoot().resolve(relative);
        try {
            writeFiles(flattened, origFile, thumbFile);
            return insertRow(uuid, itemId, imageType, relative);
        } catch (DuplicateKeyException e) {
            // 并发同键双写：uk 兜底，读回胜者（两方文件内容一致，同路径无冲突）
            ImageEntity winner = imageMapper.selectOne(new LambdaQueryWrapper<ImageEntity>()
                    .eq(ImageEntity::getClientUuid, uuid));
            if (winner != null) {
                return ImageResponse.from(winner);
            }
            throw e;
        } catch (RuntimeException e) {
            deleteQuietly(origFile);
            deleteQuietly(thumbFile);
            throw e;
        }
    }

    public List<ImageResponse> listByItem(long itemId) {
        // 作废/回收站商品的图片仍可查看（M2-6 作废重录携带原图片的前置）
        requireItem(itemId);
        return imageMapper.selectList(new LambdaQueryWrapper<ImageEntity>()
                        .eq(ImageEntity::getItemId, itemId)
                        .orderByAsc(ImageEntity::getSortOrder))
                .stream().map(ImageResponse::from).toList();
    }

    // ------------------------------------------------------------------ 校验链

    private String requireUuid(String clientUuid) {
        try {
            return UUID.fromString(clientUuid).toString();
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.VALIDATION, "clientUuid の形式が正しくありません");
        }
    }

    private ItemEntity requireItem(long itemId) {
        ItemEntity item = itemMapper.selectById(itemId);
        if (item == null) {
            throw new BizException(ErrorCode.NOT_FOUND);
        }
        return item;
    }

    /** 上传目标须未冻结：作废/回收站商品禁一切变动；不存在同样 404。 */
    private ItemEntity requireUpdatableItem(long itemId) {
        ItemEntity item = requireItem(itemId);
        if (item.getVoided() == 1 || item.getDeleted() == 1) {
            throw new BizException(ErrorCode.NOT_FOUND);
        }
        return item;
    }

    private void requireImageSlot(long itemId) {
        Long count = imageMapper.selectCount(new LambdaQueryWrapper<ImageEntity>()
                .eq(ImageEntity::getItemId, itemId));
        if (count != null && count >= MAX_IMAGES_PER_ITEM) {
            throw new BizException(ErrorCode.IMAGE_COUNT_LIMIT);
        }
    }

    private byte[] readWithinLimit(MultipartFile file) {
        if (file.getSize() > properties.maxUploadBytes()) {
            throw new BizException(ErrorCode.IMAGE_TOO_LARGE);
        }
        try {
            byte[] data = file.getBytes();
            if (data.length > properties.maxUploadBytes()) {
                throw new BizException(ErrorCode.IMAGE_TOO_LARGE);
            }
            return data;
        } catch (IOException e) {
            throw new BizException(ErrorCode.IMAGE_FORMAT_INVALID);
        }
    }

    private void requireSupportedFormat(byte[] data) {
        boolean jpeg = startsWith(data, JPEG_MAGIC);
        boolean png = startsWith(data, PNG_MAGIC);
        if (!jpeg && !png) {
            throw new BizException(ErrorCode.IMAGE_FORMAT_INVALID);
        }
    }

    /** 读头取宽高（不整图解码，像素炸弹在 OOM 前被拒）；头损坏按格式非法处理。 */
    private BufferedImage decodeWithinPixelLimit(byte[] data) {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            ImageReader reader = ImageIO.getImageReaders(in).next();
            try {
                reader.setInput(in);
                if (reader.getWidth(0) > properties.maxPixels()
                        || reader.getHeight(0) > properties.maxPixels()) {
                    throw new BizException(ErrorCode.IMAGE_PIXEL_LIMIT);
                }
                BufferedImage image = reader.read(0);
                if (image == null) {
                    throw new BizException(ErrorCode.IMAGE_FORMAT_INVALID);
                }
                return image;
            } finally {
                reader.dispose();
            }
        } catch (BizException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            // IIOException/负参等解码失败一律格式非法（头可通过、体损坏的 JPEG 走这里）
            throw new BizException(ErrorCode.IMAGE_FORMAT_INVALID);
        }
    }

    /** PNG 透明通道转 JPEG 会变黑——铺白底（品照片白底即正确呈现）。 */
    private BufferedImage flattenOnWhite(BufferedImage src) {
        if (!src.getColorModel().hasAlpha()) {
            return src;
        }
        BufferedImage rgb = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        try {
            g.setPaint(Color.WHITE);
            g.fillRect(0, 0, src.getWidth(), src.getHeight());
            g.drawImage(src, 0, 0, null);
        } finally {
            g.dispose();
        }
        return rgb;
    }

    // ------------------------------------------------------------------ 落盘与落库

    private void writeFiles(BufferedImage image, Path origFile, Path thumbFile) {
        try {
            Files.createDirectories(origFile.getParent());
            Files.createDirectories(thumbFile.getParent());
            // 原图：scale(1.0) 亦强制走编码管线（入参已是 BufferedImage，无元数据可携带）
            Thumbnails.of(image).scale(1.0).outputQuality(ORIG_QUALITY)
                    .outputFormat("JPEG").toFile(origFile.toFile());
            Thumbnails.of(image).size(THUMB_EDGE, THUMB_EDGE).outputQuality(THUMB_QUALITY)
                    .outputFormat("JPEG").toFile(thumbFile.toFile());
        } catch (IOException e) {
            throw new BizException(ErrorCode.INTERNAL, "画像ファイルの保存に失敗しました");
        }
    }

    /** 事务内 INSERT + 审计（审计与业务同生共死，docs/01 八节）。 */
    private ImageResponse insertRow(String uuid, long itemId, int imageType, String relative) {
        ImageEntity entity = txTemplate.execute(status -> {
            ImageEntity row = new ImageEntity();
            row.setItemId(itemId);
            row.setClientUuid(uuid);
            row.setStoredPath(relative);
            row.setThumbPath(relative);
            row.setImageType(imageType);
            row.setSortOrder(nextSortOrder(itemId));
            // MetaObjectHandler 不在本项目范围，操作人显式取会话上下文
            row.setCreatedBy(currentUserId());
            row.setCreatedAt(LocalDateTime.now(clock));
            imageMapper.insert(row);
            auditRecorder.record("IMAGE_UPLOAD", "item", itemId, java.util.Map.of(
                    "imageId", row.getId(),
                    "clientUuid", uuid,
                    "storedPath", relative,
                    "imageType", imageType));
            return row;
        });
        return ImageResponse.from(entity);
    }

    private Integer nextSortOrder(long itemId) {
        return imageMapper.selectList(new LambdaQueryWrapper<ImageEntity>()
                        .eq(ImageEntity::getItemId, itemId)
                        .orderByDesc(ImageEntity::getSortOrder)
                        .last("LIMIT 1"))
                .stream().map(ImageEntity::getSortOrder).findFirst().orElse(-1) + 1;
    }

    private Long currentUserId() {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        if (auth != null && auth.getPrincipal()
                instanceof com.kcgl.module.auth.KcglUserDetails operator) {
            return operator.getUserId();
        }
        throw new IllegalStateException("画像アップロードに認証コンテキストがありません");
    }

    private void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("一時画像ファイルの削除に失敗: {}", file, e);
        }
    }

    private boolean startsWith(byte[] data, byte[] magic) {
        if (data.length < magic.length) {
            return false;
        }
        for (int i = 0; i < magic.length; i++) {
            if (data[i] != magic[i]) {
                return false;
            }
        }
        return true;
    }
}
