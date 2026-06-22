package dev.thefather007.worldguardneo.integrations;

import dev.thefather007.worldguardneo.flags.Flag;
import dev.thefather007.worldguardneo.region.ProtectedRegion;
import net.minecraft.server.MinecraftServer;

import java.util.stream.Collectors;

/**
 * Builds the small HTML popup shown when a region marker is clicked on a web map (BlueMap,
 * squaremap). Lists the region's priority, owners, members and any non-default flags —
 * the "click a region → see its info/flags" feature. Kept HTML-light and self-contained so it
 * works in every map mod's info panel.
 */
final class MarkerPopup {

    private MarkerPopup() {}

    static String html(ProtectedRegion r) {
        MinecraftServer srv = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        StringBuilder sb = new StringBuilder(256);
        sb.append("<div class=\"wgn-region\">");
        sb.append("<b>").append(esc(r.id())).append("</b><br>");
        sb.append("priority: ").append(r.priority()).append("<br>");

        String owners = r.ownersView().isEmpty() ? "—" : r.ownersView().stream()
                .map(u -> dev.thefather007.worldguardneo.util.UuidResolver.nameOf(srv, u))
                .map(MarkerPopup::esc)
                .collect(Collectors.joining(", "));
        sb.append("owners: ").append(owners).append("<br>");

        if (!r.membersView().isEmpty()) {
            String members = r.membersView().stream()
                    .map(u -> dev.thefather007.worldguardneo.util.UuidResolver.nameOf(srv, u))
                    .map(MarkerPopup::esc)
                    .collect(Collectors.joining(", "));
            sb.append("members: ").append(members).append("<br>");
        }

        if (!r.flagsRaw().isEmpty()) {
            String flags = r.flagsRaw().entrySet().stream()
                    .map(e -> { Flag<?> f = e.getKey(); return esc(f.name() + "=" + f.displayRaw(e.getValue())); })
                    .collect(Collectors.joining(", "));
            sb.append("flags: ").append(flags);
        }
        sb.append("</div>");
        return sb.toString();
    }

    /** Minimal HTML escaping so region ids / names with special chars can't break the panel. */
    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
