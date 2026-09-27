package com.kcgl.module.image;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/** 商品图片（item_image）。stored_path/thumb_path 均为相对各自根目录的 "{yyyy}/{MM}/{uuid}.jpg"。 */
@TableName("item_image")
public class ImageEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long itemId;
    /** 上传幂等键（docs/01 7.0：重放一律 200 读回，绝不 409）。 */
    private String clientUuid;
    private String storedPath;
    private String thumbPath;
    /** 1品照片 2底款照片（预留）。 */
    private Integer imageType;
    private Integer sortOrder;
    private Long createdBy;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getItemId() { return itemId; }
    public void setItemId(Long itemId) { this.itemId = itemId; }
    public String getClientUuid() { return clientUuid; }
    public void setClientUuid(String clientUuid) { this.clientUuid = clientUuid; }
    public String getStoredPath() { return storedPath; }
    public void setStoredPath(String storedPath) { this.storedPath = storedPath; }
    public String getThumbPath() { return thumbPath; }
    public void setThumbPath(String thumbPath) { this.thumbPath = thumbPath; }
    public Integer getImageType() { return imageType; }
    public void setImageType(Integer imageType) { this.imageType = imageType; }
    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
