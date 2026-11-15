package work.yoavtc.displayentitieseditor.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import work.yoavtc.displayentitieseditor.DisplayEntitiesEditor;

/** Client -> server: new text (plain or tellraw-style JSON) and options for an existing text display. */
public record TextEditPayload(int entityId, String text, TextOptions options) implements CustomPacketPayload {
	public static final int MAX_TEXT = 4096;
	public static final Type<TextEditPayload> TYPE = new Type<>(DisplayEntitiesEditor.id("text_edit"));
	public static final StreamCodec<RegistryFriendlyByteBuf, TextEditPayload> CODEC = StreamCodec.of(
			(buf, p) -> {
				buf.writeVarInt(p.entityId);
				buf.writeUtf(p.text, MAX_TEXT);
				TextOptions.write(buf, p.options);
			},
			buf -> new TextEditPayload(buf.readVarInt(), buf.readUtf(MAX_TEXT), TextOptions.read(buf)));

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
