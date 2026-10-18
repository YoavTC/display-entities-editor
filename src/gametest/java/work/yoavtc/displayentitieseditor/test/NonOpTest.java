package work.yoavtc.displayentitieseditor.test;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.world.entity.Display;
import net.minecraft.world.phys.AABB;
import work.yoavtc.displayentitieseditor.DisplayEntitiesEditorConfig;
import work.yoavtc.displayentitieseditor.net.QuickActionPayload;
import work.yoavtc.displayentitieseditor.net.TransformPayload;

/** A player without operator permission (cheats off) can edit when allow_non_operators is true, and not when false. */
public class NonOpTest implements FabricClientGameTest {
	private static final AABB ALL = new AABB(-200, -64, -200, 200, 320, 200);

	private static String state(TestSingleplayerContext sp) {
		return sp.getServer().computeOnServer(server -> {
			for (var level : server.getAllLevels()) {
				for (Display d : level.getEntitiesOfClass(Display.class, ALL)) {
					return "pos=" + d.position();
				}
			}
			return "none";
		});
	}

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext sp = context.worldBuilder().create()) {
			// cheats stay OFF: the player is not an operator
			sp.getServer().runCommand("gamemode creative @a");
			sp.getServer().runCommand("give Player0 minecraft:breeze_rod");
			sp.getServer().runCommand("tp Player0 0.5 -60 0.5 0 0");
			sp.getServer().runCommand("summon block_display 0 -59 3 {block_state:\"minecraft:tnt\"}");
			context.waitTicks(40);
			int id = sp.getServer().computeOnServer(server -> {
				for (var level : server.getAllLevels()) {
					for (Display d : level.getEntitiesOfClass(Display.class, ALL)) {
						return d.getId();
					}
				}
				return -1;
			});
			boolean isOp = sp.getServer().computeOnServer(server -> server.getPlayerList().getPlayers().get(0)
					.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER));
			System.out.println("[nonop-test] player is operator: " + isOp + " (expect false)");
			System.out.println("[nonop-test] start " + state(sp));

			DisplayEntitiesEditorConfig.setAllowNonOperators(true);
			context.runOnClient(mc -> ClientPlayNetworking.send(new TransformPayload(id, 2, -59, 3, 0, 0, 0, 1, 1, 1, 1)));
			context.waitTicks(5);
			System.out.println("[nonop-test] allow_non_operators=true  -> " + state(sp) + " (expect x=2)");

			DisplayEntitiesEditorConfig.setAllowNonOperators(false);
			context.waitTicks(10);
			context.runOnClient(mc -> ClientPlayNetworking.send(new TransformPayload(id, 4, -59, 3, 0, 0, 0, 1, 1, 1, 1)));
			context.runOnClient(mc -> ClientPlayNetworking.send(new QuickActionPayload(id, QuickActionPayload.Action.DELETE)));
			context.waitTicks(5);
			System.out.println("[nonop-test] allow_non_operators=false -> " + state(sp) + " (expect unchanged, still x=2)");
			DisplayEntitiesEditorConfig.setAllowNonOperators(true);
		}
	}
}
