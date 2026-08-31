package com.github.ucchyocean.lunachat.core;

import com.github.ucchyocean.lunachat.api.ChannelDescriptor;
import com.github.ucchyocean.lunachat.api.ChannelId;
import com.github.ucchyocean.lunachat.api.ChannelPage;
import com.github.ucchyocean.lunachat.api.ChannelPageRequest;
import com.github.ucchyocean.lunachat.api.ChannelQueryService;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

public final class BoundedChannelQueryService implements ChannelQueryService {
    private final ChannelDirectory directory;
    public BoundedChannelQueryService(ChannelDirectory directory) { this.directory = directory; }
    @Override public Optional<ChannelDescriptor> find(ChannelId id) { return directory.find(id); }
    @Override public Optional<ChannelDescriptor> findByNameOrAlias(String value) { return directory.findByNameOrAlias(value); }

    @Override public ChannelPage listVisibleToIntegration(ChannelPageRequest request) {
        List<ChannelDescriptor> visible = directory.snapshot().stream()
                .filter(ChannelDescriptor::acceptsExternalMessages).toList();
        int start = decodeCursor(request.cursor());
        if (start > visible.size()) throw new IllegalArgumentException("cursor is outside current result set");
        int end = Math.min(start + request.limit(), visible.size());
        List<ChannelDescriptor> page = new ArrayList<>(visible.subList(start, end));
        return new ChannelPage(page, end < visible.size() ? encodeCursor(end) : null);
    }

    private static String encodeCursor(int offset) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(("v1:" + offset).getBytes(StandardCharsets.US_ASCII));
    }
    private static int decodeCursor(String cursor) {
        if (cursor == null) return 0;
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.US_ASCII);
            if (!decoded.startsWith("v1:")) throw new IllegalArgumentException("unsupported cursor");
            int offset = Integer.parseInt(decoded.substring(3));
            if (offset < 0) throw new IllegalArgumentException("negative cursor offset");
            return offset;
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("invalid opaque cursor", error);
        }
    }
}
