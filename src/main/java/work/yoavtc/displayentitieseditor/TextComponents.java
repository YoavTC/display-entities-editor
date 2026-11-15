package work.yoavtc.displayentitieseditor;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import java.io.StringReader;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.resources.RegistryOps;

/**
 * Turns what a player typed into the NBT a text display stores. Like {@code /tellraw}, it accepts a JSON text
 * component ({@code {"text":"Hi","bold":true,"color":"gold"}}, {@code ["a",{"text":"b","italic":true}]},
 * {@code "quoted"}) or the SNBT form of one. Anything that does not start with {@code {}, {@code [} or {@code "}
 * is plain literal text.
 */
public final class TextComponents {
	private TextComponents() {
	}

	/** Result of {@link #parse}: either the tag to store or a human readable error. */
	public record Result(Tag tag, String error) {
		static Result ok(Tag tag) {
			return new Result(tag, null);
		}

		static Result error(String message) {
			return new Result(null, message);
		}
	}

	/**
	 * The text of a component as the create/edit screen shows it: plain text when it has no formatting, otherwise
	 * its JSON. The inverse of {@link #parse}.
	 */
	public static String toEditable(Component component, HolderLookup.Provider registries) {
		JsonElement json = ComponentSerialization.CODEC.encodeStart(RegistryOps.create(JsonOps.INSTANCE, registries), component)
				.result().orElse(null);
		if (json == null) {
			return component.getString();
		}
		String plain = null;
		if (json.isJsonPrimitive() && json.getAsJsonPrimitive().isString()) {
			plain = json.getAsString();
		} else if (json.isJsonObject() && json.getAsJsonObject().size() == 1 && json.getAsJsonObject().has("text")
				&& json.getAsJsonObject().get("text").isJsonPrimitive()) {
			plain = json.getAsJsonObject().get("text").getAsString();
		}
		if (plain != null) {
			char first = plain.isEmpty() ? 'x' : plain.charAt(0);
			boolean safe = !plain.isEmpty() && plain.indexOf('\n') < 0 && first != '{' && first != '[' && first != '"';
			return safe ? plain : new com.google.gson.JsonPrimitive(plain).toString();
		}
		return json.toString();
	}

	public static Result parse(String raw, HolderLookup.Provider registries) {
		String input = raw.strip();
		if (input.isEmpty()) {
			return Result.error("Nothing to display.");
		}
		char first = input.charAt(0);
		if (first != '{' && first != '[' && first != '"') {
			return Result.ok(StringTag.valueOf(input)); // plain text
		}

		String jsonError;
		try {
			JsonReader reader = new JsonReader(new StringReader(input));
			reader.setLenient(true); // like tellraw: unquoted keys / single quotes are fine
			JsonElement json = JsonParser.parseReader(reader);
			DataResult<Component> parsed = ComponentSerialization.CODEC.parse(RegistryOps.create(JsonOps.INSTANCE, registries), json);
			if (parsed.error().isEmpty()) {
				DataResult<Tag> encoded = ComponentSerialization.CODEC.encodeStart(RegistryOps.create(NbtOps.INSTANCE, registries),
						parsed.result().orElseThrow());
				if (encoded.result().isPresent()) {
					return Result.ok(encoded.result().get());
				}
			}
			jsonError = parsed.error().map(DataResult.Error::message).orElse("could not encode the text component");
		} catch (RuntimeException | StackOverflowError e) {
			jsonError = "not valid JSON";
		}

		// not JSON: maybe SNBT (what /tellraw also accepts)
		try {
			CompoundTag wrapper = TagParser.parseCompoundFully("{v:" + input + "}");
			Tag value = wrapper.get("v");
			DataResult<Component> parsed = ComponentSerialization.CODEC.parse(RegistryOps.create(NbtOps.INSTANCE, registries), value);
			if (parsed.error().isEmpty()) {
				return Result.ok(value);
			}
			return Result.error("Invalid text component: " + parsed.error().get().message());
		} catch (CommandSyntaxException e) {
			return Result.error("Invalid text component: " + jsonError);
		}
	}
}
