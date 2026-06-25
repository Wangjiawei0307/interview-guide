package interview.guide.infrastructure.file;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.function.Predicate;

@Slf4j
@Service
public class FileValidationService {

    public void validateFile(MultipartFile file, long maxSizeBytes, String fileTypeName) {
        if (file.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                String.format("请选择要上传的%s文件", fileTypeName));
        }

        if (file.getSize() > maxSizeBytes) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "文件大小超过限制");
        }
    }

    public void validateContentTypeByList(String contentType, List<String> allowedTypes, String errorMessage) {
        if (!isAllowedType(contentType, allowedTypes)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                errorMessage != null ? errorMessage : "不支持的文件类型: " + contentType);
        }
    }

    public void validateContentType(String contentType, String fileName,
                                   Predicate<String> mimeTypeChecker,
                                   Predicate<String> extensionChecker,
                                   String errorMessage) {
        if (mimeTypeChecker.test(contentType)) {
            return;
        }

        if (fileName != null && extensionChecker.test(fileName)) {
            return;
        }

        throw new BusinessException(ErrorCode.BAD_REQUEST,
            errorMessage != null ? errorMessage : "不支持的文件类型: " + contentType);
    }

    private boolean isAllowedType(String contentType, List<String> allowedTypes) {
        if (contentType == null || allowedTypes == null || allowedTypes.isEmpty()) {
            return false;
        }

        String lowerContentType = contentType.toLowerCase();
        return allowedTypes.stream()
            .anyMatch(allowed -> {
                String lowerAllowed = allowed.toLowerCase();
                return lowerContentType.contains(lowerAllowed) || lowerAllowed.contains(lowerContentType);
            });
    }

    public boolean isMarkdownExtension(String fileName) {
        if (fileName == null) {
            return false;
        }

        String lowerFileName = fileName.toLowerCase();
        return lowerFileName.endsWith(".md") ||
               lowerFileName.endsWith(".markdown") ||
               lowerFileName.endsWith(".mdown");
    }

    public boolean isKnowledgeBaseExtension(String fileName) {
        if (fileName == null) {
            return false;
        }

        String lowerFileName = fileName.toLowerCase();
        return isMarkdownExtension(fileName) ||
               lowerFileName.endsWith(".pdf") ||
               lowerFileName.endsWith(".doc") ||
               lowerFileName.endsWith(".docx") ||
               lowerFileName.endsWith(".txt") ||
               lowerFileName.endsWith(".rtf") ||
               lowerFileName.endsWith(".xlsx") ||
               lowerFileName.endsWith(".xls") ||
               lowerFileName.endsWith(".csv") ||
               lowerFileName.endsWith(".png") ||
               lowerFileName.endsWith(".jpg") ||
               lowerFileName.endsWith(".jpeg") ||
               lowerFileName.endsWith(".webp") ||
               lowerFileName.endsWith(".gif") ||
               lowerFileName.endsWith(".bmp") ||
               lowerFileName.endsWith(".tif") ||
               lowerFileName.endsWith(".tiff");
    }

    public boolean isKnowledgeBaseMimeType(String contentType) {
        if (contentType == null) {
            return false;
        }

        String lowerContentType = contentType.toLowerCase();
        return lowerContentType.contains("pdf") ||
               lowerContentType.contains("msword") ||
               lowerContentType.contains("wordprocessingml") ||
               lowerContentType.contains("spreadsheetml") ||
               lowerContentType.contains("ms-excel") ||
               lowerContentType.contains("text/csv") ||
               lowerContentType.contains("application/csv") ||
               lowerContentType.startsWith("image/") ||
               lowerContentType.contains("text/plain") ||
               lowerContentType.contains("text/markdown") ||
               lowerContentType.contains("text/x-markdown") ||
               lowerContentType.contains("text/x-web-markdown") ||
               lowerContentType.contains("application/rtf");
    }
}
