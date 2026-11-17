package work.yoavtc.displayentitieseditor.net;

import work.yoavtc.displayentitieseditor.DisplayEntitiesEditor;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server -> client: the answer to a {@link TransformPayload} with the same {@code seq}. Carries the display's
 * position, left rotation and scale as they are on the server after the request was handled (accepted or not).
 */
public record TransformResultPayload(int entityId, int seq, double x, double y, double z,
		float qx, float qy, float qz, float qw, float sx, float sy, float sz) implements CustomPacketPayload {
	public static final Type<TransformResultPayload> TYPE = new Type<>(DisplayEntitiesEditor.id("transform_result"));
	public static final StreamCodec<RegistryFriendlyByteBuf, TransformResultPayload> CODEC = StreamCodec.of(
			(buf, p) -> {
				buf.writeVarInt(p.entityId);
				buf.writeVarInt(p.seq);
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
			buf -> new TransformResultPayload(buf.readVarInt(), buf.readVarInt(), buf.readDouble(), buf.readDouble(), buf.readDouble(),
					buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(),
					buf.readFloat(), buf.readFloat(), buf.readFloat()));

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
