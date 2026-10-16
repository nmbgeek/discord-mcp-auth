package dev.saseq.services;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

class EmojiImageTest {
    @ParameterizedTest
    @ValueSource(strings = {"png", "jpeg", "gif"})
    void acceptsRawAndDataUriImagesWithCorrectMediaType(String format) throws Exception {
        String encoded = encode(image(format, 2, 2));
        String dataUri = "data:image/" + format + ";base64," + encoded;
        assertThat(EmojiImage.from(encoded, null).getEncoding()).isEqualTo(dataUri);
        assertThat(EmojiImage.from(dataUri, null).getEncoding()).isEqualTo(dataUri);
    }

    @ParameterizedTest
    @ValueSource(strings = {"file:///etc/passwd", "http://169.254.169.254/latest/meta-data/",
            "http://127.0.0.1/", "http://[::1]/", "http://10.0.0.1/", "ftp://localhost/",
            "https://cdn.discordapp.com/example.png"})
    void rejectsEveryUrlBeforeAccessingDiscord(String url) {
        // A null JDA makes accidental dispatch fail, without connecting a bot.
        assertThatIllegalArgumentException().isThrownBy(() ->
                new EmojiService(null).createEmoji("123", "test", null, url, null))
                .withMessageContaining("URL downloads are disabled");
    }

    @Test
    void neverContactsHttpServerOrFollowsRedirectsEvenWithValidInlineImage() throws Exception {
        var requests = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            exchange.getResponseHeaders().add("Location", "http://169.254.169.254/");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
            String inline = encode(image("png", 2, 2));
            assertThatIllegalArgumentException().isThrownBy(() ->
                    new EmojiService(null).createEmoji("123", "test", inline, url, null))
                    .withMessageContaining("URL downloads are disabled");
            assertThat(requests).hasValue(0);
        } finally {
            server.stop(0);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "!invalid!", "aGVsbG8=", "data:image/svg+xml;base64,PHN2Zz4=",
            "data:image/png,abc", "data:image/png;base64", "data:image/png;base64,"})
    void rejectsMalformedAndNonImageInputs(String input) {
        assertThatIllegalArgumentException().isThrownBy(() -> EmojiImage.from(input, null));
    }

    @Test
    void rejectsMissingImage() {
        assertThatIllegalArgumentException().isThrownBy(() -> EmojiImage.from(null, null));
    }

    @Test
    void rejectsMimeMismatchAndTruncatedImage() throws Exception {
        byte[] png = image("png", 2, 2);
        assertThatIllegalArgumentException().isThrownBy(() ->
                EmojiImage.from("data:image/jpeg;base64," + encode(png), null))
                .withMessageContaining("does not match");
        assertThatIllegalArgumentException().isThrownBy(() ->
                EmojiImage.from(encode(Arrays.copyOf(png, 33)), null));
    }

    @Test
    void boundsEncodedAndDecodedSizesIncludingBase64PaddingBoundary() throws Exception {
        assertThatIllegalArgumentException().isThrownBy(() ->
                EmojiImage.from("!".repeat(EmojiImage.MAX_BASE64_LENGTH + 1), null))
                .withMessageContaining("256 KiB");
        assertThatIllegalArgumentException().isThrownBy(() ->
                EmojiImage.from("data:image/png;base64," + "A".repeat(EmojiImage.MAX_BASE64_LENGTH + 1), null))
                .withMessageContaining("256 KiB");
        byte[] bytes = Arrays.copyOf(image("png", 2, 2), EmojiImage.MAX_BYTES);
        assertThat(EmojiImage.from(encode(bytes), null)).isNotNull();
        String oversized = encode(Arrays.copyOf(bytes, EmojiImage.MAX_BYTES + 1));
        assertThat(oversized.length()).isEqualTo(EmojiImage.MAX_BASE64_LENGTH);
        assertThatIllegalArgumentException().isThrownBy(() -> EmojiImage.from(oversized, null))
                .withMessageContaining("256 KiB");
    }

    @Test
    void boundsPixelAllocationBeforeDecoding() throws Exception {
        assertThat(EmojiImage.from(encode(image("png", 1024, 1024)), null)).isNotNull();
        for (int[] dimensions : new int[][]{{1025, 1}, {1, 1025}}) {
            String encoded = encode(image("png", dimensions[0], dimensions[1]));
            assertThatIllegalArgumentException().isThrownBy(() -> EmojiImage.from(encoded, null))
                    .withMessageContaining("1024 pixels");
        }
    }

    private static byte[] image(String format, int width, int height) throws Exception {
        var output = new ByteArrayOutputStream();
        assertThat(ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), format, output)).isTrue();
        return output.toByteArray();
    }

    private static String encode(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }
}
