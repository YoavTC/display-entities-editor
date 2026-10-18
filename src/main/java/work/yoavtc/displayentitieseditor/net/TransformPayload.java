package work.yoavtc.displayentitieseditor.net;

import work.yoavtc.displayentitieseditor.DisplayEntitiesEditor;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client -> server: absolute position, left rotation (x,y,z,w) and scale of a display entity. */
public record TransformPayload(int entityId, double x, double y, double z,
		float qx, float qy, float qz, float qw, float sx, float sy, float sz) implements CustomPacketPayload {
	public static final Type<TransformPayload> TYPE = new Type<>(DisplayEntitiesEditor.id("transform"));
	public static final StreamCodec<RegistryFriendlyByteBuf, TransformPayload> CODEC = StreamCodec.of(
			(buf, p) -> {
				buf.writeVarInt(p.entityId);
				buf.writeDouble(p.x);
				buf.writeDouble(p.y);
				buf.writeDouble(p.z);
				buf.writeFloat(p.qx);
				buf.writeFloat(p.qy);
				buf.writeFloat(p.qz);
				buf.writeFloat(p.qw);
				buf.writeFloat(p.sx);
				buf.writeFloat(p.sy);
				buf.writeFloat(p.sz);
			},
			buf -> new TransformPayload(buf.readVarInt(), buf.readDouble(), buf.readDouble(), buf.readDouble(),
					buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(),
					buf.readFloat(), buf.readFloat(), buf.readFloat()));

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
