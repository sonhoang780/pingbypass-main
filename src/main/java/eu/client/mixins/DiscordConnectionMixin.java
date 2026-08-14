package eu.client.mixins;

import meteordevelopment.discordipc.connection.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

// RPC ("gradlew runClient" shows presence under the 1.21.4 project, Java 21 target, but silently
// fails -- DiscordIPC.start() returns false -- under this 26.1.2 project, Java 25 target, even
// with Discord genuinely running (verified live: `Get-ChildItem \\.\pipe\` shows discord-ipc-0
// actually open) and even after adding --enable-native-access=ALL-UNNAMED to runClient's jvmArgs
// (that fix was speculative/untested and didn't work -- see build.gradle's own comment, kept for
// now since it's harmless but this mixin is the real fix).
//
// Root cause, read straight from discord-ipc:1.1's own sources jar (Connection.java's open(),
// Windows branch): it opens `new WinConnection("\\\\?\\pipe\\discord-ipc-" + i, callback)` --
// WinConnection wraps that path in a plain java.io.RandomAccessFile(name, "rw").
// "\\?\pipe\..." is the Win32 extended-length/DOS-device path prefix; java.io.File/
// RandomAccessFile's own path canonicalization (WinNTFileSystem) is well known to mishandle that
// prefix for a named pipe specifically (there's no real filesystem entry to canonicalize against,
// unlike a real "\\?\C:\..." long file path) and this appears to have gotten stricter between the
// two projects' JDK targets (21 -> 25) -- RandomAccessFile's constructor throws,
// Connection.open()'s `catch (IOException ignored) {}` silently swallows it for all 10 attempted
// indices, and DiscordIPC.start() just returns false with zero diagnostic (this project's own
// onError callback is never reached -- that only fires for errors AFTER a connection opens). The
// classic working prefix for a Windows named pipe is "\\.\pipe\..." (the regular Win32 device
// namespace), not "\\?\pipe\...".
//
// Can't patch the third-party jar directly. First attempt used @ModifyConstant on the literal --
// failed injection (0/1 matched, no refmap): "\\\\?\\pipe\\discord-ipc-" + i is built with
// invokedynamic string concatenation (javac's default since Java 9), so the raw prefix is baked
// into the indy bootstrap's constant-pool recipe, not a plain LDC @ModifyConstant can see.
// @ModifyArg instead intercepts the fully-built String argument at the WinConnection
// constructor call site, after concatenation has already happened -- works regardless of how the
// string got built.
@Mixin(Connection.class)
public class DiscordConnectionMixin {
    @ModifyArg(
            method = "open",
            at = @At(value = "INVOKE",
                    target = "Lmeteordevelopment/discordipc/connection/WinConnection;<init>(Ljava/lang/String;Ljava/util/function/Consumer;)V"),
            index = 0
    )
    private static String euclient$fixWindowsPipePrefix(String name) {
        return name.replace("\\\\?\\pipe\\", "\\\\.\\pipe\\");
    }
}
