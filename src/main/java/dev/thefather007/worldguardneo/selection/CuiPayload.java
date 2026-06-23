package dev.thefather007.worldguardneo.selection;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.nio.charset.StandardCharsets;

/**
 * A single WorldEdit-CUI plugin-channel message ({@code worldedit:cui}). WGN speaks the CUI
 * protocol itself (no WorldEdit dep): the server pushes the selection as command strings (e.g.
 * {@code s|cuboid}, {@code p|0|10|64|10|343}) that WorldEditCUI renders.
 *
 * <p>Wire format: the body is the command as raw UTF-8 bytes with no length prefix (the CUI
 * protocol predates Minecraft's length-prefixed payloads), so we read/write the buffer directly.
 * The channel is optional and send-only ({@code playToClient}), so clients without WorldEditCUI
 * never negotiate it and the sends drop harmlessly.
 */
public record CuiPayload(String command) implements CustomPacketPayload {

    /** Channel id — must match what the WorldEditCUI client listens on. */
    public static final ResourceLocation CHANNEL =
            ResourceLocation.fromNamespaceAndPath("worldedit", "cui");

    public static final CustomPacketPayload.Type<CuiPayload> TYPE =
            new CustomPacketPayload.Type<>(CHANNEL);

    /** Raw-bytes codec: UTF-8 with no length prefix. Decode is unused (send-only) but required. */
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
