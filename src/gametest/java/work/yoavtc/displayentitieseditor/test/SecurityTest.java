package work.yoavtc.displayentitieseditor.test;

import work.yoavtc.displayentitieseditor.net.TransformPayload;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.world.entity.Display;
import net.minecraft.world.phys.AABB;

/** Sends packets a modified client could send and checks the server clamps, rejects or reverts them. */
public class SecurityTest implements FabricClientGameTest {
	private static final AABB ALL = new AABB(-500, -64, -500, 500, 320, 500);

	private static String state(TestSingleplayerContext sp) {
		return sp.getServer().computeOnServer(server -> {
			for (var level : server.getAllLevels()) {
				for (Display d : level.getEntitiesOfClass(Display.class, ALL)) {
					return "pos=" + d.position() + " scale=" + Display.createTransformation(d.getEntityData()).scale();
				}
			}
			return "none";
		});
	}

	private static int id(TestSingleplayerContext sp) {
		return sp.getServer().computeOnServer(server -> {
			for (var level : server.getAllLevels()) {
				for (Display d : level.getEntitiesOfClass(Display.class, ALL)) {
					return d.getId();
				}
			}
			return -1;
		});
	}

	private static void send(ClientGameTestContext c, int id, double x, double y, double z, float scale) {
		c.runOnClient(mc -> ClientPlayNetworking.send(new TransformPayload(id, x, y, z, 0, 0, 0, 1, scale, scale, scale)));
		c.waitTicks(3);
	}

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext sp = context.worldBuilder().create()) {
			sp.getServer().runOnServer(server -> ((net.minecraft.client.server.IntegratedServer) server).setWorldAllowCommands(true));
			sp.getServer().runCommand("gamemode creative @a");
			sp.getServer().runCommand("give Player0 minecraft:breeze_rod");
			sp.getServer().runCommand("tp Player0 0.5 -60 0.5 0 0");
			sp.getServer().runCommand("forceload add 0 0 16 16");
			sp.getServer().runCommand("summon block_display 0 -59 3 {block_state:\"minecraft:tnt\"}");
			context.waitTicks(40);
			int id = id(sp);
			System.out.println("[sec-test] start " + state(sp));

			send(context, id, 2, -59, 3, 1000f);
			System.out.println("[sec-test] forged scale 1000 -> " + state(sp) + "  (expect scale 1)");
			send(context, id, Double.NaN, -59, 3, 1);
			send(context, id, 2, 99999, 3, 1);
			context.waitTicks(10);
			System.out.println("[sec-test] NaN / absurd y -> " + state(sp) + "  (expect unchanged)");

			sp.getServer().runCommand("clear Player0");
			context.waitTicks(5);
			send(context, id, 1, -59, 3, 1);
			context.waitTicks(10);
			System.out.println("[sec-test] without wand -> " + state(sp) + "  (expect unchanged)");
			sp.getServer().runCommand("give Player0 minecraft:breeze_rod");
			context.waitTicks(5);

			sp.getServer().runCommand("tp Player0 100.5 -60 5.5");
			context.waitTicks(40);
			send(context, id, 1, -59, 3, 1);
			context.waitTicks(10);
			System.out.println("[sec-test] player 100 blocks away -> " + state(sp) + "  (expect unchanged)");

			sp.getServer().runCommand("tp Player0 0.5 -60 0.5");
			context.waitTicks(40);
			context.runOnClient(mc -> {
				for (int i = 0; i < 300; i++) {
					ClientPlayNetworking.send(new TransformPayload(id, 0.001 * i, -59, 3, 0, 0, 0, 1, 1, 1, 1));
				}
			});
			context.waitTicks(10);
			System.out.println("[sec-test] 300 packets in one tick -> " + state(sp) + "  (rate limited: x about 0.079)");
		}
	}
}
