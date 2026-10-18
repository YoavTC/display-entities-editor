package work.yoavtc.displayentitieseditor.client.mixin;

import work.yoavtc.displayentitieseditor.client.GizmoController;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.phys.EntityHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Minecraft.class)
public class MinecraftMixin {
	/**
	 * Right-click belongs to the gizmo while the wand is held (no block placing / item use),
	 * except when aiming at an item frame with nothing selected: that is the frame conversion.
	 */
	@Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
	private void display_entities_editor$blockUse(CallbackInfo ci) {
		if (!GizmoController.active) {
			return;
		}
		Minecraft mc = (Minecraft) (Object) this;
		boolean frameTarget = mc.hitResult instanceof EntityHitResult hit && hit.getEntity() instanceof ItemFrame;
		if (!(frameTarget && GizmoController.selected == null)) {
			ci.cancel();
		}
	}

	/** The attack button also belongs to the editor while the wand is held. */
	@Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
	private void display_entities_editor$blockAttack(CallbackInfoReturnable<Boolean> cir) {
		if (GizmoController.active) {
			cir.setReturnValue(false);
		}
	}

	@Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
	private void display_entities_editor$blockContinueAttack(boolean down, CallbackInfo ci) {
		if (GizmoController.active) {
			ci.cancel();
		}
	}
}
