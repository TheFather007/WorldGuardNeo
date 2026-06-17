package dev.thefather007.worldguardneo.selection;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.nio.charset.StandardCharsets;

/**
 * A single WorldEdit-CUI plugin-channel message ({@code worldedit:cui}).
 *
 * <p>WorldGuardNeo no longer depends on WorldEdit, so it speaks the CUI protocol itself: the
 * server pushes the current selection to the client as a sequence of these payloads, and the
 * <a href="https://www.curseforge.com/minecraft/mc-mods/worldedit-cui">WorldEditCUI</a> client
 * mod renders the outline. Each payload is a single CUI command string such as {@code s|cuboid}
 * or {@code p|0|10|64|10|343}.
 *
 * <p><b>Wire format.</b> The CUI protocol predates Minecraft's length-prefixed custom payloads:
 * the body is the command string as <em>raw</em> UTF-8 bytes with no length prefix. We therefore
 * read/write the byte buffer directly rather than via {@code writeUtf}.
 *
 * <p>The channel is registered {@link
 * net.neoforged.neoforge.network.registration.PayloadRegistrar#optional() optional} and only ever
 * sent {@code playToClient}: a vanilla client (or one without WorldEditCUI) simply never negotiates
 * the channel, so the sends are dropped harmlessly and protection is unaffected.
 */
public record CuiPayload(String command) implements CustomPacketPayload {

    /** Channel id — must match what the WorldEditCUI client listens on. */
    public static final ResourceLocation CHANNEL =
            ResourceLocation.fromNamespaceAndPath("worldedit", "cui");

    public static final CustomPacketPayload.Type<CuiPayload> TYPE =
            new CustomPacketPayload.Type<>(CHANNEL);

    /**
     * Raw-bytes codec: writes the command string's UTF-8 bytes with no length prefix, and on
     * decode consumes whatever bytes remain in the buffer. We never actually decode server-side
     * (the channel is send-only), but a codec must provide both directions.
     */
    public static final StreamCodec<RegistryFriendlyByteBuf, CuiPayload> CODEC =
            CustomPacketPayload.codec(
                    (payload, buf) -> buf.writeBytes(payload.command.getBytes(StandardCharsets.UTF_8)),
                    buf -> {
                        byte[] bytes = new byte[buf.readableBytes()];
                        buf.readBytes(bytes);
                        return new CuiPayload(new String(bytes, StandardCharsets.UTF_8));
                    });

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
