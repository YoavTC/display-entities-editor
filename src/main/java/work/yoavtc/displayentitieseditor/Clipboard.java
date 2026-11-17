package work.yoavtc.displayentitieseditor;

import com.mojang.math.Transformation;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Display;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.TagValueOutput;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Per-player copy / paste of a display's look. The copy lives on the server (taken from the real entity), so a
 * paste can only ever apply values some display really had, never data made up by a client.
 * <p>
 * Paste always applies rotation (left/right rotation and the entity's yaw/pitch) and scale, keeping the target's
 * visual centre in place. On top of that: the block state if both are block displays of the same block, the head
 * texture (profile component) if both are player head item displays, and every other option if both are the same
 * type of display.
 */
final class Clipboard {
	/** Options every display has. Position, translation and the edit-smoothed teleport_duration are not copied. */
	private static final String[] COMMON_OPTIONS = {
			"billboard", "brightness", "view_range", "shadow_radius", "shadow_strength", "glow_color_override",
			"width", "height", "interpolation_duration"};
	private static final String[] TEXT_OPTIONS = {
			"line_width", "background", "text_opacity", "shadow", "see_through", "default_background", "alignment"};
	private static final String[] ITEM_OPTIONS = {"item_display"};

	private enum Kind {
		BLOCK, ITEM, TEXT;

		static Kind of(Display d) {
			return d instanceof Display.BlockDisplay ? BLOCK : d instanceof Display.ItemDisplay ? ITEM : TEXT;
		}
	}

	private record Entry(Kind kind, CompoundTag tag, Quaternionf left, Quaternionf right, Vector3f scale, float yRot,
			float xRot, BlockState blockState, boolean head, ResolvableProfile profile) {
	}

	private static final Map<UUID, Entry> CLIPBOARDS = new HashMap<>();

	private Clipboard() {
	}

	static void copy(ServerPlayer player, Display display) {
		TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, player.level().registryAccess());
		display.saveWithoutId(out);
		Transformation t = Display.createTransformation(display.getEntityData());
		BlockState blockState = display instanceof Display.BlockDisplay block ? block.getBlockState() : null;
		ItemStack stack = display instanceof Display.ItemDisplay item ? item.getItemStack() : ItemStack.EMPTY;
		boolean head = stack.is(Items.PLAYER_HEAD);
		CLIPBOARDS.put(player.getUUID(), new Entry(Kind.of(display), out.buildResult(), new Quaternionf(t.leftRotation()),
				new Quaternionf(t.rightRotation()), new Vector3f(t.scale()), display.getYRot(), display.getXRot(),
				blockState, head, head ? stack.get(DataComponents.PROFILE) : null));
	}

	static void paste(ServerPlayer player, Display target) {
		Entry e = CLIPBOARDS.get(player.getUUID());
		if (e == null) {
			return; // nothing copied yet
		}
		Kind kind = Kind.of(target);

		if (kind == e.kind()) {
			String[] typeOptions = kind == Kind.TEXT ? TEXT_OPTIONS : kind == Kind.ITEM ? ITEM_OPTIONS : new String[0];
			QuickActions.modify(player, target, tag -> {
				copyKeys(e.tag(), tag, COMMON_OPTIONS);
				copyKeys(e.tag(), tag, typeOptions);
			});
		}
		if (target instanceof Display.BlockDisplay block && e.blockState() != null
				&& block.getBlockState().getBlock() == e.blockState().getBlock()) {
			block.setBlockState(e.blockState());
		}
		if (target instanceof Display.ItemDisplay item && e.head() && item.getItemStack().is(Items.PLAYER_HEAD)) {
			ItemStack stack = item.getItemStack().copy();
			if (e.profile() != null) {
				stack.set(DataComponents.PROFILE, e.profile());
			} else {
				stack.remove(DataComponents.PROFILE);
			}
			item.setItemStack(stack);
		}

		target.setYRot(e.yRot());
		target.setXRot(e.xRot());
		QuickActions.retransform(target, t -> new Transformation(new Vector3f(t.translation()), new Quaternionf(e.left()),
				new Vector3f(e.scale()), new Quaternionf(e.right())));
	}

	/** Copies the given top-level keys; a key the source does not have is removed, so the target falls back to the default too. */
	private static void copyKeys(CompoundTag from, CompoundTag to, String[] keys) {
		for (String key : keys) {
			Tag value = from.get(key);
			if (value != null) {
				to.put(key, value.copy());
			} else {
				to.remove(key);
			}
		}
	}

	static void forget(UUID player) {
		CLIPBOARDS.remove(player);
	}
}
