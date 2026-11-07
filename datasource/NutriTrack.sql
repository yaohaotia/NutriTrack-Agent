-- NutriTrack schema-only initializer. No exported account or meal data.

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ----------------------------
-- Table structure for daily_diet_analysis
-- ----------------------------
DROP TABLE IF EXISTS `daily_diet_analysis`;
CREATE TABLE `daily_diet_analysis`  (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '分析报告的唯一ID',
  `user_id` bigint(20) NOT NULL COMMENT '关联的用户ID',
  `analysis_date` date NOT NULL COMMENT '分析的日期',
  `health_score` decimal(5, 2) NULL DEFAULT NULL COMMENT '健康度评分 (例如 0-100.00)',
  `carb_grams` decimal(10, 2) NULL DEFAULT NULL COMMENT '碳水化合物总克数',
  `protein_grams` decimal(10, 2) NULL DEFAULT NULL COMMENT '蛋白质总克数',
  `fat_grams` decimal(10, 2) NULL DEFAULT NULL COMMENT '脂肪总克数',
  `nutrition_summary` text  NULL COMMENT '营养文字分析总结',
  `health_suggestions` text  NULL COMMENT '宏观健康建议',
  `improvement_suggestions` text  NULL COMMENT '具体饮食改进建议',
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '报告生成时间',
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '报告更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_user_date`(`user_id`, `analysis_date`) USING BTREE,
  INDEX `idx_user_id`(`user_id`) USING BTREE,
  INDEX `idx_analysis_date`(`analysis_date`) USING BTREE,
  CONSTRAINT `fk_daily_diet_analysis_users` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE ON UPDATE NO ACTION
) ENGINE = InnoDB AUTO_INCREMENT = 2     COMMENT = '用户的每日饮食汇总分析报告' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Data omitted from the public repository; initialize accounts locally.
-- ----------------------------

-- ----------------------------
-- Table structure for meal_images
-- ----------------------------
DROP TABLE IF EXISTS `meal_images`;
CREATE TABLE `meal_images`  (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '图片唯一ID',
  `record_id` bigint(20) NOT NULL COMMENT '关联的饮食记录ID',
  `image_url` varchar(255)  NOT NULL COMMENT '图片存储的URL',
  `upload_order` int(11) NOT NULL DEFAULT 0 COMMENT '图片排序，用于前端展示',
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_record_id`(`record_id`) USING BTREE,
  CONSTRAINT `fk_meal_images_meal_records` FOREIGN KEY (`record_id`) REFERENCES `meal_records` (`id`) ON DELETE CASCADE ON UPDATE NO ACTION
) ENGINE = InnoDB AUTO_INCREMENT = 29     COMMENT = '饮食记录的图片' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Data omitted from the public repository; initialize accounts locally.
-- ----------------------------

-- ----------------------------
-- Table structure for meal_records
-- ----------------------------
DROP TABLE IF EXISTS `meal_records`;
CREATE TABLE `meal_records`  (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '记录唯一ID',
  `user_id` bigint(20) NOT NULL COMMENT '关联的用户ID',
  `meal_type` enum('breakfast','lunch','dinner','snack')  NOT NULL COMMENT '餐别 (早餐, 午餐, 晚餐, 加餐)',
  `description` text  NULL COMMENT '用户填写的文字描述',
  `calories` int(10) UNSIGNED NULL DEFAULT NULL COMMENT '估算的热量（单位：大卡 kcal）',
  `rating` decimal(2, 1) NULL DEFAULT NULL COMMENT '用户评分 (例如 4.5, 5.0)',
  `record_date` date NOT NULL COMMENT '记录的日期',
  `is_shared` tinyint(1) NOT NULL DEFAULT 0 COMMENT '是否分享到社区 (0:不分享, 1:分享)',
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_user_id`(`user_id`) USING BTREE,
  INDEX `idx_record_date`(`record_date`) USING BTREE,
  INDEX `idx_is_shared`(`is_shared`) USING BTREE,
  CONSTRAINT `fk_meal_records_users` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE ON UPDATE NO ACTION
) ENGINE = InnoDB AUTO_INCREMENT = 9     COMMENT = '用户饮食记录表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Data omitted from the public repository; initialize accounts locally.
-- ----------------------------

-- ----------------------------
-- Table structure for notifications
-- ----------------------------
DROP TABLE IF EXISTS `notifications`;
CREATE TABLE `notifications`  (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '通知唯一ID',
  `user_id` bigint(20) NOT NULL COMMENT '接收通知的用户ID',
  `sender_user_id` bigint(20) NOT NULL COMMENT '触发通知的用户ID (系统消息时为系统用户ID)',
  `notification_type` enum('meal_reminder','system','like','comment')  NOT NULL COMMENT '通知类型',
  `target_record_id` bigint(20) NULL DEFAULT NULL COMMENT '关联的饮食记录ID, 用于跳转',
  `content` varchar(255)  NULL DEFAULT NULL COMMENT '通知内容摘要',
  `is_read` tinyint(1) NOT NULL DEFAULT 0 COMMENT '是否已读 (0:未读, 1:已读)',
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_user_id_is_read`(`user_id`, `is_read`) USING BTREE,
  INDEX `idx_sender_user_id`(`sender_user_id`) USING BTREE,
  INDEX `idx_target_record_id`(`target_record_id`) USING BTREE,
  CONSTRAINT `fk_notifications_meal_records` FOREIGN KEY (`target_record_id`) REFERENCES `meal_records` (`id`) ON DELETE CASCADE ON UPDATE NO ACTION,
  CONSTRAINT `fk_notifications_receiver_users` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE ON UPDATE NO ACTION,
  CONSTRAINT `fk_notifications_sender_users` FOREIGN KEY (`sender_user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE ON UPDATE NO ACTION
) ENGINE = InnoDB AUTO_INCREMENT = 10     COMMENT = '通用用户通知表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Data omitted from the public repository; initialize accounts locally.
-- ----------------------------

-- ----------------------------
-- Table structure for record_comments
-- ----------------------------
DROP TABLE IF EXISTS `record_comments`;
CREATE TABLE `record_comments`  (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '评论唯一ID',
  `record_id` bigint(20) NOT NULL COMMENT '被评论的饮食记录ID',
  `user_id` bigint(20) NOT NULL COMMENT '评论的用户ID',
  `content` text  NOT NULL COMMENT '评论内容',
  `parent_comment_id` bigint(20) NULL DEFAULT NULL COMMENT '回复的父评论ID，NULL为一级评论',
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '评论时间',
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '评论更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_record_id`(`record_id`) USING BTREE,
  INDEX `idx_user_id`(`user_id`) USING BTREE,
  INDEX `idx_parent_comment_id`(`parent_comment_id`) USING BTREE,
  CONSTRAINT `fk_record_comments_meal_records` FOREIGN KEY (`record_id`) REFERENCES `meal_records` (`id`) ON DELETE CASCADE ON UPDATE NO ACTION,
  CONSTRAINT `fk_record_comments_parent_comment` FOREIGN KEY (`parent_comment_id`) REFERENCES `record_comments` (`id`) ON DELETE CASCADE ON UPDATE NO ACTION,
  CONSTRAINT `fk_record_comments_users` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE ON UPDATE NO ACTION
) ENGINE = InnoDB AUTO_INCREMENT = 5     COMMENT = '饮食记录评论表（支持楼中楼）' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Data omitted from the public repository; initialize accounts locally.
-- ----------------------------

-- ----------------------------
-- Table structure for record_likes
-- ----------------------------
DROP TABLE IF EXISTS `record_likes`;
CREATE TABLE `record_likes`  (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '点赞记录唯一ID',
  `record_id` bigint(20) NOT NULL COMMENT '被点赞的饮食记录ID',
  `user_id` bigint(20) NOT NULL COMMENT '点赞的用户ID',
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '点赞时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_user_record`(`user_id`, `record_id`) USING BTREE COMMENT '联合唯一索引，防止重复点赞',
  INDEX `idx_record_id`(`record_id`) USING BTREE,
  INDEX `idx_user_id`(`user_id`) USING BTREE,
  CONSTRAINT `fk_record_likes_meal_records` FOREIGN KEY (`record_id`) REFERENCES `meal_records` (`id`) ON DELETE CASCADE ON UPDATE NO ACTION,
  CONSTRAINT `fk_record_likes_users` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE ON UPDATE NO ACTION
) ENGINE = InnoDB AUTO_INCREMENT = 6     COMMENT = '饮食记录点赞表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Data omitted from the public repository; initialize accounts locally.
-- ----------------------------

-- ----------------------------
-- Table structure for users
-- ----------------------------
DROP TABLE IF EXISTS `users`;
CREATE TABLE `users`  (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '用户唯一ID',
  `username` varchar(50)  NOT NULL COMMENT '用户名，用于登录',
  `password` varchar(255)  NOT NULL COMMENT '重要：存储Bcrypt等算法加密后的密码哈希值，严禁存储明文！',
  `email` varchar(100)  NULL DEFAULT NULL COMMENT '邮箱，可用于登录或找回密码',
  `phone_number` varchar(20)  NULL DEFAULT NULL COMMENT '手机号，可用于登录或找回密码',
  `nickname` varchar(50)  NULL DEFAULT NULL COMMENT '用户昵称，在社区显示',
  `avatar_url` varchar(255)  NULL DEFAULT NULL COMMENT '头像图片URL',
  `status` enum('active','inactive','suspended','deleted')  NOT NULL DEFAULT 'active' COMMENT '用户状态: active-活跃, ina-未激活, suspended-被封禁, deleted-已注销',
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '注册时间',
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '信息更新时间',
  `deleted_at` timestamp NULL DEFAULT NULL COMMENT '执行注销操作的时间，status不为deleted时为NULL',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_username`(`username`) USING BTREE,
  UNIQUE INDEX `uk_email`(`email`) USING BTREE,
  UNIQUE INDEX `uk_phone_number`(`phone_number`) USING BTREE,
  INDEX `idx_status`(`status`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 10     COMMENT = '用户信息表（包含软删除逻辑）' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Data omitted from the public repository; initialize accounts locally.
-- ----------------------------

SET FOREIGN_KEY_CHECKS = 1;
