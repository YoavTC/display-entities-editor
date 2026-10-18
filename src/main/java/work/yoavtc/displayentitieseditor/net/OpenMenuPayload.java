package work.yoavtc.displayentitieseditor.net;

import work.yoavtc.displayentitieseditor.DisplayEntitiesEditor;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Server -> client: the wand was used on an empty item frame; ask which display type to create. */
public record OpenMenuPayload(int frameId) implements CustomPacketPayload {
	public static final Type<OpenMenuPayload> TYPE = new Type<>(DisplayEntitiesEditor.id("open_menu"));
	public static final StreamCodec<RegistryFriendlyByteBuf, OpenMenuPayload> CODEC = StreamCodec.of(
			(buf, p) -> buf.writeVarInt(p.frameId),
			buf -> new OpenMenuPayload(buf.readVarInt()));

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
