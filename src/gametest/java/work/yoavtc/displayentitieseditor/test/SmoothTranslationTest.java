package work.yoavtc.displayentitieseditor.test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.world.entity.Display;
import net.minecraft.world.phys.AABB;
import work.yoavtc.displayentitieseditor.net.TransformPayload;

/**
 * A (forged) drag that steps the display 1 block every 50 ms must show intermediate positions on the client, and the
 * display's own teleport_duration (7 here) must come back afterwards.
 */
public class SmoothTranslationTest implements FabricClientGameTest {
	private static final AABB ALL = new AABB(-200, -64, -200, 200, 320, 200);

	private static int serverDuration(TestSingleplayerContext sp) {
		return sp.getServer().computeOnServer(server -> {
			for (var level : server.getAllLevels()) {
				for (Display d : level.getEntitiesOfClass(Display.class, ALL)) {
					return d.getEntityData().get(durationAccessor());
				}
			}
			return -1;
		});
	}

	@SuppressWarnings("unchecked")
	private static net.minecraft.network.syncher.EntityDataAccessor<Integer> durationAccessor() {
		try {
			var f = Display.class.getDeclaredField("DATA_POS_ROT_INTERPOLATION_DURATION_ID");
			f.setAccessible(true);
			return (net.minecraft.network.syncher.EntityDataAccessor<Integer>) f.get(null);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext sp = context.worldBuilder().create()) {
			sp.getServer().runOnServer(server -> ((net.minecraft.client.server.IntegratedServer) server).setWorldAllowCommands(true));
			sp.getServer().runCommand("gamemode creative @a");
			sp.getServer().runCommand("give Player0 minecraft:breeze_rod");
			sp.getServer().runCommand("tp Player0 0.5 -60 0.5 0 0");
			sp.getServer().runCommand("summon block_display 0 -59 3 {block_state:\"minecraft:tnt\",teleport_duration:7}");
			context.waitTicks(40);
			int id = sp.getServer().computeOnServer(server -> {
				for (var level : server.getAllLevels()) {
					for (Display d : level.getEntitiesOfClass(Display.class, ALL)) {
						return d.getId();
					}
				}
				return -1;
			});
			System.out.println("[smooth-test] original teleport_duration=" + serverDuration(sp));

			List<Double> samples = new ArrayList<>();
			for (int i = 1; i <= 10; i++) {
				final double x = i;
				context.runOnClient(mc -> ClientPlayNetworking.send(new TransformPayload(id, x, -59, 3, 0, 0, 0, 1, 1, 1, 1)));
				context.runOnClient(mc -> samples.add(clientX(mc, id)));
				context.waitTicks(1);
				context.runOnClient(mc -> samples.add(clientX(mc, id)));
			}
			System.out.println("[smooth-test] during-edit duration=" + serverDuration(sp) + " (expect 3)");
			for (int i = 0; i < 12; i++) {
				context.runOnClient(mc -> samples.add(clientX(mc, id)));
				context.waitTicks(1);
			}
			StringBuilder sb = new StringBuilder();
			java.util.Set<String> distinct = new java.util.TreeSet<>();
			for (double s : samples) {
				String v = String.format(Locale.ROOT, "%.2f", s);
				sb.append(v).append(' ');
				distinct.add(v);
			}
			System.out.println("[smooth-test] client x per tick: " + sb);
			int fractional = 0;
			for (double s : samples) {
				if (Math.abs(s - Math.round(s)) > 0.05) {
					fractional++;
				}
			}
			System.out.println("[smooth-test] samples=" + samples.size() + " distinct=" + distinct.size() + " intermediate(non-integer)=" + fractional);
			context.waitTicks(10);
			int restored = serverDuration(sp);
			System.out.println("[smooth-test] restored teleport_duration=" + restored + " (expect 7)");
			if (fractional < 5 || restored != 7) {
				throw new AssertionError("smooth translation failed: intermediate=" + fractional + " restored=" + restored);
			}
		}
	}

	private static double clientX(net.minecraft.client.Minecraft mc, int id) {
		var e = mc.level.getEntity(id);
		return e == null ? Double.NaN : e.getPosition(mc.getDeltaTracker().getGameTimeDeltaPartialTick(false)).x;
	}
}
