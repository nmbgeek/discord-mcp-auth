package dev.saseq.services;

import net.dv8tion.jda.api.entities.Icon;

import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.Locale;

/** Validates bounded inline images without opening files or network connections. */
final class EmojiImage {
    static final int MAX_BYTES = 256 * 1024;
    static final int MAX_BASE64_LENGTH = 4 * ((MAX_BYTES + 2) / 3);
    static final int MAX_DIMENSION = 1024;

    private EmojiImage() {
    }

    static Icon from(String image, String imageUrl) {
        // Keep the legacy parameter only to give existing clients an actionable error.
        if (imageUrl != null && !imageUrl.isEmpty()) {
            throw new IllegalArgumentException("Emoji URL downloads are disabled. Supply a base64 PNG, JPEG, or GIF in 'image'.");
        }
        if (image == null || image.isEmpty()) {
            throw new IllegalArgumentException("Provide a base64 PNG, JPEG, or GIF in 'image'.");
        }
        if (image.length() > MAX_BASE64_LENGTH + 32) {
            throw new IllegalArgumentException("Image exceeds the 256 KiB limit.");
        }

        String payload = image;
        String declaredFormat = null;
        if (image.startsWith("data:")) {
            int comma = image.indexOf(',');
            if (comma < 0 || comma > 32) {
                throw new IllegalArgumentException("Expected a base64 PNG, JPEG, or GIF data URI.");
            }
            declaredFormat = switch (image.substring(0, comma)) {
                case "data:image/png;base64" -> "png";
                case "data:image/jpeg;base64" -> "jpeg";
                case "data:image/gif;base64" -> "gif";
                default -> throw new IllegalArgumentException("Expected a base64 PNG, JPEG, or GIF data URI.");
            };
            payload = image.substring(comma + 1);
        }
        if (payload.length() > MAX_BASE64_LENGTH) {
            throw new IllegalArgumentException("Image exceeds the 256 KiB limit.");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(payload);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Image contains invalid base64 data.");
        }
        if (bytes.length > MAX_BYTES) {
            throw new IllegalArgumentException("Image exceeds the 256 KiB limit.");
        }

        // Avoid ImageIO's default disk cache. Inspect dimensions before decoding pixels.
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new IllegalArgumentException("Image must be a valid PNG, JPEG, or GIF.");
            }
            var reader = readers.next();
            try {
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                Icon.IconType type = switch (format) {
                    case "png" -> Icon.IconType.PNG;
                    case "jpeg" -> Icon.IconType.JPEG;
                    case "gif" -> Icon.IconType.GIF;
                    default -> throw new IllegalArgumentException("Image must be a valid PNG, JPEG, or GIF.");
                };
                if (declaredFormat != null && !declaredFormat.equals(format)) {
                    throw new IllegalArgumentException("Image content does not match the data URI type.");
                }
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width < 1 || height < 1 || width > MAX_DIMENSION || height > MAX_DIMENSION) {
                    throw new IllegalArgumentException("Emoji dimensions must be between 1 and 1024 pixels on each side.");
                }
                if (reader.read(0) == null) {
                    throw new IllegalArgumentException("Image must be a valid PNG, JPEG, or GIF.");
                }
                return Icon.from(bytes, type);
            } finally {
                reader.dispose();
            }
        } catch (IOException ex) {
            throw new IllegalArgumentException("Image must be a valid PNG, JPEG, or GIF.");
        }
    }
}
