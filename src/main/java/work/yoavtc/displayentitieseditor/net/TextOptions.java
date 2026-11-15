package work.yoavtc.displayentitieseditor.net;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Every setting of a text display that is not the text itself. The create/edit screen always sends the whole set,
 * and {@link #applyTo} writes it into the display's NBT.
 *
 * @param background    whether the text has a background at all
 * @param backgroundRgb the background colour as 0xRRGGBB (its opacity is kept, or 25% for a new one)
 * @param alignment     index into {@link #ALIGNMENTS}
 * @param billboard     index into {@link #BILLBOARDS}
 * @param opacity       text opacity 0-255
 */
public record TextOptions(boolean background, int backgroundRgb, boolean shadow, int alignment, int billboard, int opacity) {
	public static final String[] ALIGNMENTS = {"center", "left", "right"};
	public static final String[] BILLBOARDS = {"fixed", "vertical", "horizontal", "center"};
	/** Vanilla's default text background: black at 25% opacity. */
	public static final int DEFAULT_BACKGROUND = 0x40000000;

	public static void write(FriendlyByteBuf buf, TextOptions o) {
		buf.writeBoolean(o.background);
		buf.writeInt(o.backgroundRgb);
		buf.writeBoolean(o.shadow);
		buf.writeVarInt(o.alignment);
		buf.writeVarInt(o.billboard);
		buf.writeVarInt(o.opacity);
	}

	public static TextOptions read(FriendlyByteBuf buf) {
		return new TextOptions(buf.readBoolean(), buf.readInt(), buf.readBoolean(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
	}

	/** Clamps everything into its valid range (the values come from the client). */
	public TextOptions sanitized() {
		return new TextOptions(background, backgroundRgb & 0xFFFFFF, shadow,
				Math.floorMod(alignment, ALIGNMENTS.length), Math.floorMod(billboard, BILLBOARDS.length),
				Math.max(0, Math.min(255, opacity)));
	}

	/** Writes these settings into a text display's NBT (as saved by the entity). */
	public void applyTo(CompoundTag tag) {
		TextOptions o = sanitized();
		int alpha = tag.getInt("background").orElse(DEFAULT_BACKGROUND) >>> 24;
		if (alpha == 0) {
			alpha = DEFAULT_BACKGROUND >>> 24;
		}
		tag.putBoolean("default_background", false);
		tag.putInt("background", o.background ? (alpha << 24) | o.backgroundRgb : 0);
		tag.putBoolean("shadow", o.shadow);
		tag.putString("alignment", ALIGNMENTS[o.alignment]);
		tag.putString("billboard", BILLBOARDS[o.billboard]);
		tag.putByte("text_opacity", (byte) o.opacity);
	}
}
