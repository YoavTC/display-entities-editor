package work.yoavtc.displayentitieseditor.client;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.ItemStack;

/** Reads the current head texture back out of an item: {@code components."minecraft:profile".properties[0].value}. */
final class HeadTextures {
	private HeadTextures() {
	}

	/** The Base64 texture value of the stack, or an empty string if it has none. */
	static String currentValue(ItemStack stack, HolderLookup.Provider registries) {
		try {
			Tag encoded = ItemStack.CODEC.encodeStart(RegistryOps.create(NbtOps.INSTANCE, registries), stack).result().orElse(null);
			if (!(encoded instanceof CompoundTag item)) {
				return "";
			}
			return item.getCompound("components")
					.flatMap(components -> components.getCompound("minecraft:profile"))
					.flatMap(profile -> profile.getList("properties"))
					.flatMap(properties -> properties.isEmpty() ? java.util.Optional.<CompoundTag>empty() : properties.getCompound(0))
					.flatMap(property -> property.getString("value"))
					.orElse("");
		} catch (RuntimeException e) {
			return "";
		}
	}
}
