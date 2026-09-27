package com.kcgl.module.image;

import com.kcgl.common.web.ApiResponse;
import com.kcgl.module.image.dto.ImageResponse;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 图片端点（M2-5）：POST 幂等上传（clientUuid，重放 200 读回——docs/01 7.0）；
 * 按商品列出（缩略图 URL 优先，原图按需）。E+ 上传，全员可看。
 */
@RestController
@RequestMapping("/api")
public class ImageController {

    private final ImageService imageService;

    public ImageController(ImageService imageService) {
        this.imageService = imageService;
    }

    @PostMapping(value = "/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
    public ApiResponse<ImageResponse> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam("clientUuid") String clientUuid,
            @RequestParam("itemId") long itemId,
            @RequestParam(value = "imageType", defaultValue = "1") int imageType) {
        return ApiResponse.ok(imageService.upload(file, clientUuid, itemId, imageType));
    }

    @GetMapping("/items/{id}/images")
    public ApiResponse<List<ImageResponse>> listByItem(@PathVariable("id") long itemId) {
        return ApiResponse.ok(imageService.listByItem(itemId));
    }
}
