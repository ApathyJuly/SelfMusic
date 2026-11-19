package com.selfmusic.server.media;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Service
public class MediaStreamService {

    public Range parseRange(String header, long size) {
        if (header == null || header.isBlank()) {
            return null;
        }
        if (!header.startsWith("bytes=") || header.contains(",")) {
            throw new InvalidRangeException(size);
        }
        String spec = header.substring("bytes=".length()).trim();
        int dash = spec.indexOf('-');
        if (dash < 0) {
            throw new InvalidRangeException(size);
        }
        String startPart = spec.substring(0, dash).trim();
        String endPart = spec.substring(dash + 1).trim();
        try {
            long start;
            long end;
            if (startPart.isEmpty()) {
                long suffix = Long.parseLong(endPart);
                if (suffix <= 0) {
                    throw new InvalidRangeException(size);
                }
                start = Math.max(0, size - suffix);
                end = size - 1;
            } else {
                start = Long.parseLong(startPart);
                end = endPart.isEmpty() ? size - 1 : Long.parseLong(endPart);
            }
            if (size <= 0 || start < 0 || start >= size || end < start) {
                throw new InvalidRangeException(size);
            }
            end = Math.min(end, size - 1);
            return new Range(start, end);
        } catch (NumberFormatException exception) {
            throw new InvalidRangeException(size);
        }
    }

    public void copy(InputStream input, OutputStream output, long length) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long remaining = length;
        while (remaining > 0) {
            int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read < 0) {
                break;
            }
            output.write(buffer, 0, read);
            remaining -= read;
        }
        output.flush();
    }

    public String contentDisposition(String filename) {
        String safe = filename == null || filename.isBlank() ? "audio" : filename;
        String ascii = safe.replaceAll("[^\\x20-\\x7E]", "_")
                .replaceAll("[\\\\/:*?\"<>|\\r\\n]", "_");
        String encoded = URLEncoder.encode(safe, StandardCharsets.UTF_8).replace("+", "%20");
        return "attachment; filename=\"" + ascii + "\"; filename*=UTF-8''" + encoded;
    }

    public record Range(long start, long end) {
        public long length() {
            return end - start + 1;
        }
    }
}
