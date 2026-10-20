package work.yoavtc.displayentitieseditor.test;

import work.yoavtc.displayentitieseditor.client.GizmoController;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.KeyMapping;
import net.minecraft.world.entity.Display;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Smoke test: select a block display, drag/rotate/scale it, and take screenshots of each gizmo mode. */
public class GizmoClientTest implements FabricClientGameTest {
	private static Vec3 serverPos(TestSingleplayerContext sp) {
		return sp.getServer().computeOnServer(server -> {
			for (var level : server.getAllLevels()) {
				for (Display d : level.getEntitiesOfClass(Display.class, new AABB(-1000, -64, -1000, 1000, 320, 1000))) {
					return d.position();
				}
			}
			return null;
		});
	}

	private static String serverTransform(TestSingleplayerContext sp) {
		return sp.getServer().computeOnServer(server -> {
			for (var level : server.getAllLevels()) {
				for (Display d : level.getEntitiesOfClass(Display.class, new AABB(-1000, -64, -1000, 1000, 320, 1000))) {
					var t = Display.createTransformation(d.getEntityData());
					return "pos=" + d.position() + " left=" + t.leftRotation() + " scale=" + t.scale();
				}
			}
			return null;
		});
	}

	private static void rmb(ClientGameTestContext context, boolean down) {
		context.runOnClient(mc -> KeyMapping.set(mc.options.keyUse.getDefaultKey(), down));
	}

	private static void aimAt(ClientGameTestContext context, Vec3 target, float yawOffset, float pitchOffset) {
		float[] angles = context.computeOnClient(mc -> {
			Vec3 eye = mc.player.getEyePosition();
			double dx = target.x - eye.x, dy = target.y - eye.y, dz = target.z - eye.z;
			double hd = Math.sqrt(dx * dx + dz * dz);
			return new float[] {(float) Math.toDegrees(Math.atan2(-dx, dz)), (float) -Math.toDegrees(Math.atan2(dy, hd))};
		});
		context.runOnClient(mc -> {
			mc.player.setYRot(angles[0] + yawOffset);
			mc.player.setXRot(angles[1] + pitchOffset);
		});
	}

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext sp = context.worldBuilder().create()) {
			sp.getServer().runOnServer(server -> ((net.minecraft.client.server.IntegratedServer) server).setWorldAllowCommands(true));
			sp.getServer().runCommand("gamemode creative @a");
			sp.getServer().runCommand("tp @a 0 -60 0 0 0");
			sp.getServer().runCommand("execute as @p at @p run summon block_display ~ ~1 ~3 {block_state:\"minecraft:tnt\"}");
			context.waitTicks(40);

			Vec3 start = serverPos(sp);
			Vec3 center = start.add(0.5, 0.5, 0.5);
			System.out.println("[gizmo-test] start " + serverTransform(sp));
			aimAt(context, center, 0, 0);
			context.waitTicks(10);
			context.takeScreenshot("0-plain");

			sp.getServer().runCommand("give Player0 minecraft:breeze_rod");
			context.waitTicks(5);
			System.out.println("[gizmo-test] active while holding wand: " + context.computeOnClient(mc -> GizmoController.active));
			rmb(context, true);
			context.waitTicks(4);
			rmb(context, false);
			context.waitTicks(4);
			System.out.println("[gizmo-test] selected: " + context.computeOnClient(mc -> GizmoController.selected != null));
			context.takeScreenshot("1-translate");

			// drag the centre handle by turning the camera while holding RMB
			rmb(context, true);
			context.waitTicks(3);
			for (int i = 1; i <= 5; i++) {
				aimAt(context, center, -3f * i, 0);
				context.waitTicks(2);
			}
			rmb(context, false);
			context.waitTicks(15);
			System.out.println("[gizmo-test] after drag " + serverTransform(sp));
			context.takeScreenshot("2-after-drag");

			// ---- rotate about Z by dragging the Z ring
			context.runOnClient(mc -> GizmoController.setMode(GizmoController.Mode.ROTATE));
			context.waitTicks(3);
			Vec3 o = context.computeOnClient(mc -> GizmoController.pivotWorld());
			double dist = context.computeOnClient(mc -> mc.gameRenderer.mainCamera().position().distanceTo(o));
			double size = Math.max(0.3, dist * 0.22);
			double r = size * 0.9;
			System.out.println("[gizmo-test] pivot before rotate " + o);
			aimAt(context, o.add(r * Math.cos(Math.toRadians(45)), r * Math.sin(Math.toRadians(45)), 0), 0, 0);
			context.waitTicks(4);
			context.takeScreenshot("3-rotate");
			rmb(context, true);
			context.waitTicks(3);
			for (int deg = 60; deg <= 105; deg += 15) {
				double a = Math.toRadians(deg);
				aimAt(context, o.add(r * Math.cos(a), r * Math.sin(a), 0), 0, 0);
				context.waitTicks(2);
			}
			rmb(context, false);
			context.waitTicks(15);
			System.out.println("[gizmo-test] after rotate " + serverTransform(sp));
			Vec3 o2 = context.computeOnClient(mc -> GizmoController.pivotWorld());
			System.out.println("[gizmo-test] pivot after rotate " + o2 + " (moved " + o2.distanceTo(o) + ")");
			context.takeScreenshot("4-after-rotate");

			// ---- scale along the local X axis
			context.runOnClient(mc -> GizmoController.setMode(GizmoController.Mode.SCALE));
			context.waitTicks(3);
			double ang = Math.toRadians(60);
			Vec3 dirX = new Vec3(Math.cos(ang), Math.sin(ang), 0);
			aimAt(context, o2.add(dirX.scale(0.6 * size)), 0, 0);
			context.waitTicks(4);
			context.takeScreenshot("5-scale");
			rmb(context, true);
			context.waitTicks(3);
			for (int i = 1; i <= 4; i++) {
				aimAt(context, o2.add(dirX.scale((0.6 + 0.15 * i) * size)), 0, 0);
				context.waitTicks(2);
			}
			rmb(context, false);
			context.waitTicks(15);
			System.out.println("[gizmo-test] after scale " + serverTransform(sp));
			System.out.println("[gizmo-test] pivot after scale " + context.computeOnClient(mc -> GizmoController.pivotWorld()));
			context.takeScreenshot("6-after-scale");

			// ---- item frame -> block display through the prompts
			context.runOnClient(mc -> GizmoController.selected = null);
			sp.getServer().runCommand("setblock 3 -60 3 minecraft:stone");
			sp.getServer().runCommand("summon item_frame 3 -60 4 {Facing:3b}");
			context.waitTicks(10);
			Vec3 frame = sp.getServer().computeOnServer(server -> {
				for (var level : server.getAllLevels()) {
					for (var f : level.getEntitiesOfClass(net.minecraft.world.entity.decoration.ItemFrame.class, new AABB(-100, -64, -100, 100, 320, 100))) {
						return f.position();
					}
				}
				return null;
			});
			System.out.println("[gizmo-test] frame at " + frame);
			aimAt(context, frame, 0, 0);
			context.waitTicks(4);
			context.takeScreenshot("7-frame-highlight");
			System.out.println("[gizmo-test] hit=" + context.computeOnClient(mc -> mc.hitResult + " sel=" + GizmoController.selected + " active=" + GizmoController.active + " main=" + mc.player.getMainHandItem()));
			context.runOnClient(mc -> {
				var f = mc.level.getEntitiesOfClass(net.minecraft.world.entity.decoration.ItemFrame.class, new AABB(-100, -64, -100, 100, 320, 100)).get(0);
				mc.gameMode.interact(mc.player, f, new net.minecraft.world.phys.EntityHitResult(f), net.minecraft.world.InteractionHand.MAIN_HAND);
			});
			context.waitForScreen(work.yoavtc.displayentitieseditor.client.ChooseTypeScreen.class);
			context.takeScreenshot("8-choose");
			context.clickScreenButton("Block Display");
			context.waitForScreen(work.yoavtc.displayentitieseditor.client.BrowserScreen.class);
			context.getInput().typeChars("gold_block");
			context.waitTicks(3);
			context.takeScreenshot("9-browser");
			double[] px = context.computeOnClient(mc -> {
				var sc = mc.gui.screen();
				double scale = mc.getWindow().getGuiScale();
				return new double[] {((sc.width - 240) / 2 + 10) * scale, 60 * scale};
			});
			context.getInput().setCursorPos(px[0], px[1]);
			context.waitTicks(3);
			context.getInput().pressMouse(1);
			context.waitTicks(20);
			int[] counts = sp.getServer().computeOnServer(server -> {
				int frames = 0, displays = 0;
				for (var level : server.getAllLevels()) {
					frames += level.getEntitiesOfClass(net.minecraft.world.entity.decoration.ItemFrame.class, new AABB(-100, -64, -100, 100, 320, 100)).size();
					displays += level.getEntitiesOfClass(Display.class, new AABB(-100, -64, -100, 100, 320, 100)).size();
				}
				return new int[] {frames, displays};
			});
			System.out.println("[gizmo-test] after convert: frames=" + counts[0] + " displays=" + counts[1]);
			context.takeScreenshot("10-converted");

			// ---- item display through the browser, then a text display
			for (int round = 0; round < 2; round++) {
				boolean text = round == 1;
				int fx = text ? 7 : 5;
				sp.getServer().runCommand("setblock " + fx + " -60 3 minecraft:stone");
				sp.getServer().runCommand("summon item_frame " + fx + " -60 4 {Facing:3b}");
				context.waitTicks(10);
				context.runOnClient(mc -> GizmoController.selected = null);
				final int fxx = fx;
				context.runOnClient(mc -> {
					var f = mc.level.getEntitiesOfClass(net.minecraft.world.entity.decoration.ItemFrame.class, new AABB(fxx - 0.5, -64, 3, fxx + 1.5, 320, 5)).get(0);
					mc.gameMode.interact(mc.player, f, new net.minecraft.world.phys.EntityHitResult(f), net.minecraft.world.InteractionHand.MAIN_HAND);
				});
				context.waitForScreen(work.yoavtc.displayentitieseditor.client.ChooseTypeScreen.class);
				if (text) {
					context.clickScreenButton("Text Display");
					context.waitForScreen(work.yoavtc.displayentitieseditor.client.ContentScreen.class);
					context.getInput().typeChars("Hello");
					context.clickScreenButton("Confirm");
				} else {
					context.clickScreenButton("Item Display");
					context.waitForScreen(work.yoavtc.displayentitieseditor.client.BrowserScreen.class);
					context.getInput().typeChars("diamond");
					context.waitTicks(3);
					double[] ipx = context.computeOnClient(mc -> {
						var sc = mc.gui.screen();
						double scale = mc.getWindow().getGuiScale();
						return new double[] {((sc.width - 240) / 2 + 10) * scale, 60 * scale};
					});
					context.getInput().setCursorPos(ipx[0], ipx[1]);
					context.waitTicks(3);
					context.getInput().pressMouse(1);
				}
				context.waitTicks(20);
				System.out.println("[gizmo-test] round " + round + " displays=" + sp.getServer().computeOnServer(server -> {
					int n = 0;
					for (var level : server.getAllLevels()) {
						n += level.getEntitiesOfClass(Display.class, new AABB(-100, -64, -100, 100, 320, 100)).size();
					}
					return n;
				}));
				System.out.println("[gizmo-test] display rot " + sp.getServer().computeOnServer(server -> {
					StringBuilder sb = new StringBuilder();
					for (var level : server.getAllLevels()) {
						for (Display d : level.getEntitiesOfClass(Display.class, new AABB(fxx - 0.5, -64, 3, fxx + 1.5, 320, 5))) {
							sb.append(d.getClass().getSimpleName()).append(" yRot=").append(d.getYRot()).append(" xRot=").append(d.getXRot()).append(" pos=").append(d.position());
						}
					}
					return sb.toString();
				}));
				context.waitTicks(3);
				sp.getServer().runCommand("tp Player0 " + (fx + 0.5) + " -60 6.5");
				context.waitTicks(10);
				aimAt(context, new Vec3(fx + 0.5, -59.5, 4.0), 0, 0);
				context.waitTicks(5);
				context.takeScreenshot(text ? "12-text-front" : "11-item-front");
				sp.getServer().runCommand("tp Player0 " + (fx + 0.5) + " -60 6.5");
				context.waitTicks(10);
				aimAt(context, new Vec3(fx + 0.5, -59.5, 4.0), 0, 0);
				context.waitTicks(5);
				context.takeScreenshot(text ? "12-text-back" : "11-item-back");
			}

			// ---- text from a frame lying on the floor
			sp.getServer().runCommand("setblock 11 -60 3 minecraft:stone");
			sp.getServer().runCommand("summon item_frame 11 -59 3 {Facing:1b}");
			context.waitTicks(10);
			sp.getServer().runCommand("tp Player0 11.5 -60 6.5");
			context.waitTicks(10);
			context.runOnClient(mc -> GizmoController.selected = null);
			context.runOnClient(mc -> {
				var f = mc.level.getEntitiesOfClass(net.minecraft.world.entity.decoration.ItemFrame.class, new AABB(10.5, -61, 2, 12.5, -57, 5)).get(0);
				mc.gameMode.interact(mc.player, f, new net.minecraft.world.phys.EntityHitResult(f), net.minecraft.world.InteractionHand.MAIN_HAND);
			});
			context.waitForScreen(work.yoavtc.displayentitieseditor.client.ChooseTypeScreen.class);
			context.clickScreenButton("Text Display");
			context.waitForScreen(work.yoavtc.displayentitieseditor.client.ContentScreen.class);
			context.getInput().typeChars("Floor text");
			context.clickScreenButton("Confirm");
			context.waitTicks(20);
			context.runOnClient(mc -> GizmoController.selected = null);
			aimAt(context, new Vec3(11.5, -58.75, 3.5), 0, 0);
			context.waitTicks(5);
			context.takeScreenshot("13-floor-text");

			// ---- item from a frame lying on the floor
			sp.getServer().runCommand("setblock 15 -60 3 minecraft:stone");
			sp.getServer().runCommand("summon item_frame 15 -59 3 {Facing:1b}");
			context.waitTicks(10);
			sp.getServer().runCommand("tp Player0 15.5 -60 6.5");
			context.waitTicks(10);
			context.runOnClient(mc -> GizmoController.selected = null);
			context.runOnClient(mc -> {
				var f = mc.level.getEntitiesOfClass(net.minecraft.world.entity.decoration.ItemFrame.class, new AABB(14.5, -61, 2, 16.5, -57, 5)).get(0);
				mc.gameMode.interact(mc.player, f, new net.minecraft.world.phys.EntityHitResult(f), net.minecraft.world.InteractionHand.MAIN_HAND);
			});
			context.waitForScreen(work.yoavtc.displayentitieseditor.client.ChooseTypeScreen.class);
			context.clickScreenButton("Item Display");
			context.waitForScreen(work.yoavtc.displayentitieseditor.client.BrowserScreen.class);
			context.getInput().typeChars("diamond");
			context.waitTicks(3);
			double[] fpx = context.computeOnClient(mc -> {
				var sc = mc.gui.screen();
				double scale = mc.getWindow().getGuiScale();
				return new double[] {((sc.width - 240) / 2 + 10) * scale, 60 * scale};
			});
			context.getInput().setCursorPos(fpx[0], fpx[1]);
			context.waitTicks(3);
			context.getInput().pressMouse(1);
			context.waitTicks(20);
			context.runOnClient(mc -> GizmoController.selected = null);
			aimAt(context, new Vec3(15.5, -58.5, 3.5), 0, 0);
			context.waitTicks(5);
			context.takeScreenshot("15-floor-item");

			// ---- everything with the real mouse: type button, then a cell deep in the unfiltered grid
			sp.getServer().runCommand("setblock 13 -60 3 minecraft:stone");
			sp.getServer().runCommand("summon item_frame 13 -60 4 {Facing:3b}");
			context.waitTicks(10);
			sp.getServer().runCommand("tp Player0 13.5 -60 6.5");
			context.waitTicks(10);
			context.runOnClient(mc -> GizmoController.selected = null);
			context.runOnClient(mc -> {
				var f = mc.level.getEntitiesOfClass(net.minecraft.world.entity.decoration.ItemFrame.class, new AABB(12.5, -61, 2, 14.5, -57, 5)).get(0);
				mc.gameMode.interact(mc.player, f, new net.minecraft.world.phys.EntityHitResult(f), net.minecraft.world.InteractionHand.MAIN_HAND);
			});
			context.waitForScreen(work.yoavtc.displayentitieseditor.client.ChooseTypeScreen.class);
			context.clickScreenButton("Block Display");
			context.waitForScreen(work.yoavtc.displayentitieseditor.client.BrowserScreen.class);
			context.waitTicks(5);
			double[] cell = context.computeOnClient(mc -> {
				var sc = mc.gui.screen();
				double scale = mc.getWindow().getGuiScale();
				return new double[] {((sc.width - 240) / 2 + 4 * 20 + 10) * scale, (50 + 2 * 20 + 10) * scale};
			});
			context.getInput().setCursorPos(cell[0], cell[1]);
			context.waitTicks(3);
			context.takeScreenshot("14-browser-hover");
			context.getInput().pressMouse(1);
			context.waitTicks(20);
			System.out.println("[gizmo-test] full mouse flow screen closed=" + context.computeOnClient(mc -> mc.gui.screen() == null) + " displays="
					+ sp.getServer().computeOnServer(server -> {
						int n = 0;
						for (var level : server.getAllLevels()) {
							n += level.getEntitiesOfClass(Display.class, new AABB(-100, -64, -100, 100, 320, 100)).size();
						}
						return n;
					}));

				// ---- unselected: centre markers for several displays (block, item, text)
				context.runOnClient(mc -> GizmoController.selected = null);
				sp.getServer().runCommand("tp Player0 9.5 -60 9.5");
				context.waitTicks(10);
				aimAt(context, new Vec3(9.5, -59.3, 3.5), 0, 0);
				context.waitTicks(5);
				context.takeScreenshot("16-centre-markers");
		}
	}
}
