package com.runpulse.server.controller.admin;

import com.runpulse.server.controller.admin.AdminResponse;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * 管理后台：APK 文件上传。
 */
@RestController
@RequestMapping("/admin/api/upload")
public class FileUploadController {

    /** APK 存储目录，默认为 /opt/runpulse/apk/ */
    @Value("${runpulse.apk.upload-dir:/opt/runpulse/apk}")
    private String uploadDir;

    /** APK 访问基础 URL，默认为 http://47.116.205.225:18080/apk/ */
    @Value("${runpulse.apk.base-url:http://47.116.205.225:18080/apk}")
    private String baseUrl;

    /** 上传 APK 文件，返回下载地址和 MD5 */
    @PostMapping("/apk")
    public AdminResponse<Map<String, Object>> uploadApk(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件不能为空");
        }

        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || !originalFilename.toLowerCase().endsWith(".apk")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "只支持 APK 文件");
        }

        try {
            // 确保目录存在
            Path dir = Paths.get(uploadDir);
            if (!Files.exists(dir)) {
                Files.createDirectories(dir);
            }

            // 生成唯一文件名
            String filename = UUID.randomUUID().toString().substring(0, 8) + "-" + originalFilename;
            Path targetPath = dir.resolve(filename);

            // 保存文件
            file.transferTo(targetPath.toFile());

            // 计算 MD5
            String md5 = calculateMd5(targetPath);

            // 返回下载地址
            String downloadUrl = baseUrl + "/" + filename;

            return AdminResponse.ok(Map.of(
                    "url", downloadUrl,
                    "filename", filename,
                    "size", file.getSize(),
                    "md5", md5
            ));

        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "文件上传失败: " + e.getMessage());
        }
    }

    private String calculateMd5(Path path) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(Files.readAllBytes(path));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("MD5 algorithm not available", e);
        }
    }
}
