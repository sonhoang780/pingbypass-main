import sys
import re

content = open('src/main/java/eu/client/mixins/ClientPlayerEntityMixin.java', 'r', encoding='utf-8').read()

target_pre = '''    @Inject(method = "sendPosition", at = @At("HEAD"))
    private void silentSync(CallbackInfo ci) {
        if (!EUClient.ROTATION_MANAGER.isSilentSyncRequired()) return;

        this.originalSilentXRot = this.getXRot();
        this.xRotLast -= 4;
        float f = (float) ((Math.random() * 2.0 - 1.0) * 0.001f);
        float f2 = net.minecraft.util.Mth.clamp(this.originalSilentXRot + f, -90.0F, 90.0F);
        this.setXRot(f2);
    }'''

replacement_pre = '''    @Inject(method = "sendPosition", at = @At("HEAD"), cancellable = true)
    private void silentSync(CallbackInfo ci) {
        if (!EUClient.ROTATION_MANAGER.isSilentSyncRequired()) return;

        // PAPER/GRIMAC FIX: We ALREADY sent an immediate PosRot packet in silentRotate.
        // We MUST NOT let vanilla send another MovePlayerPacket in this exact same tick,
        // otherwise Paper kicks for packet spam.
        EUClient.ROTATION_MANAGER.setSilentSyncRequired(false);
        ci.cancel();
    }'''

content = content.replace(target_pre, replacement_pre)

target_post = '''    @Inject(method = "sendPosition", at = @At("RETURN"))
    private void silentSync(CallbackInfo ci) {
        if (!EUClient.ROTATION_MANAGER.isSilentSyncRequired()) return;

        this.setXRot(this.originalSilentXRot);
        EUClient.ROTATION_MANAGER.setSilentSyncRequired(false);
    }'''

content = content.replace(target_post, '')

with open('src/main/java/eu/client/mixins/ClientPlayerEntityMixin.java', 'w', encoding='utf-8') as f:
    f.write(content)
