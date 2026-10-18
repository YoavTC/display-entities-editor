package work.yoavtc.displayentitieseditor.test;

import work.yoavtc.displayentitieseditor.client.ContentScreen;
import work.yoavtc.displayentitieseditor.client.GizmoController;
import work.yoavtc.displayentitieseditor.client.QuickMenuScreen;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.KeyMapping;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Display;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Sneak+click a player head item display, "Set Texture", and check item.components."minecraft:profile".properties[0].value. */
public class HeadTextureTest implements FabricClientGameTest {
	private static final AABB ALL = new AABB(-200, -64, -200, 200, 320, 200);

	private static void aimAt(ClientGameTestContext context, Vec3 target) {
		float[] a = context.computeOnClient(mc -> {
			Vec3 eye = mc.player.getEyePosition();
			double dx = target.x - eye.x, dy = target.y - eye.y, dz = target.z - eye.z;
			return new float[] {(float) Math.toDegrees(Math.atan2(-dx, dz)), (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)))};
		});
		context.runOnClient(mc -> {
			mc.player.setYRot(a[0]);
			mc.player.setXRot(a[1]);
		});
	}

	private static String itemNbt(TestSingleplayerContext sp) {
		return sp.getServer().computeOnServer(server -> {
			for (var level : server.getAllLevels()) {
				for (Display d : level.getEntitiesOfClass(Display.class, ALL)) {
					TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
					d.saveWithoutId(out);
					return String.valueOf(out.buildResult().get("item"));
				}
			}
			return "none";
		});
	}

	private static void sneakClick(ClientGameTestContext context, Vec3 target) {
		context.runOnClient(mc -> KeyMapping.set(mc.options.keyShift.getDefaultKey(), true));
		context.waitTicks(4);
		aimAt(context, target);
		context.waitTicks(3);
		context.runOnClient(mc -> KeyMapping.set(mc.options.keyUse.getDefaultKey(), true));
		context.waitTicks(4);
		context.runOnClient(mc -> KeyMapping.set(mc.options.keyUse.getDefaultKey(), false));
		context.runOnClient(mc -> KeyMapping.set(mc.options.keyShift.getDefaultKey(), false));
		context.waitForScreen(QuickMenuScreen.class);
	}

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext sp = context.worldBuilder().create()) {
			sp.getServer().runOnServer(server -> ((net.minecraft.client.server.IntegratedServer) server).setWorldAllowCommands(true));
			sp.getServer().runCommand("gamemode creative @a");
			sp.getServer().runCommand("give Player0 minecraft:breeze_rod");
			sp.getServer().runCommand("tp Player0 0.5 -60 0.5 0 0");
			sp.getServer().runCommand("summon item_display 0 -58.5 3 {item:{id:\"minecraft:player_head\",count:1}}");
			context.waitTicks(40);
			Vec3 center = new Vec3(0.5, -58.5, 3.5);
			System.out.println("[head-test] before: " + itemNbt(sp));

			sneakClick(context, center);
			context.takeScreenshot("h1-menu");
			context.clickScreenButton("Set Texture");
			context.waitForScreen(ContentScreen.class);
			context.getInput().typeChars("http://textures.minecraft.net/texture/dd3a7ae5d23d1e8f2ec1a2bd6f2d3e7a6b0f0e6c1c7a5d3a4d9a1c2b3e4f5a6b");
			context.waitTicks(3);
			context.takeScreenshot("h2-input");
			context.clickScreenButton("Confirm");
			context.waitTicks(20);
			System.out.println("[head-test] after URL: " + itemNbt(sp));

			// a raw Base64 value, and an invalid one that must be refused
			String b64 = java.util.Base64.getEncoder().encodeToString(
					"{\"textures\":{\"SKIN\":{\"url\":\"http://textures.minecraft.net/texture/abc123\"}}}".getBytes());
			sneakClick(context, center);
			context.clickScreenButton("Set Texture");
			context.waitForScreen(ContentScreen.class);
			context.getInput().typeChars(b64);
			context.clickScreenButton("Confirm");
			context.waitTicks(20);
			System.out.println("[head-test] after base64: " + itemNbt(sp));

			sneakClick(context, center);
			context.clickScreenButton("Set Texture");
			context.waitForScreen(ContentScreen.class);
			context.getInput().typeChars("not a texture!!");
			context.clickScreenButton("Confirm");
			context.waitTicks(20);
			System.out.println("[head-test] after invalid: " + itemNbt(sp) + " (expect unchanged)");

			// the option is hidden for non-head items
			sp.getServer().runCommand("data modify entity @e[type=minecraft:item_display,limit=1] item set value {id:\"minecraft:diamond\",count:1}");
			context.waitTicks(10);
			sneakClick(context, center);
			boolean hasButton = context.computeOnClient(mc -> mc.gui.screen().children().stream()
					.anyMatch(c -> c instanceof net.minecraft.client.gui.components.Button b && b.getMessage().getString().equals("Set Texture")));
			System.out.println("[head-test] Set Texture shown for a diamond: " + hasButton + " (expect false)");
		}
	}
}
