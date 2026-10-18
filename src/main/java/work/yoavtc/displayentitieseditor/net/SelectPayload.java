package work.yoavtc.displayentitieseditor.net;

import work.yoavtc.displayentitieseditor.DisplayEntitiesEditor;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Server -> client: select this entity (sent after a duplicate so the copy can be moved immediately). */
public record SelectPayload(int entityId) implements CustomPacketPayload {
	public static final Type<SelectPayload> TYPE = new Type<>(DisplayEntitiesEditor.id("select"));
	public static final StreamCodec<RegistryFriendlyByteBuf, SelectPayload> CODEC = StreamCodec.of(
			(buf, p) -> buf.writeVarInt(p.entityId),
			buf -> new SelectPayload(buf.readVarInt()));

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
