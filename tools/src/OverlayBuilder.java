import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.iface.*;
import org.jf.dexlib2.immutable.ImmutableDexFile;
import org.jf.dexlib2.writer.pool.DexPool;
import org.caustic.patcher.core.DexPatches;

/** Build-time extraction of explicitly selected members; no original APK bundled. */
public class OverlayBuilder {
    public static void main(String[] args) throws Exception {
        Set<String> selected = new HashSet<>(Files.readAllLines(Paths.get(args[1])));
        List<ClassDef> classes = new ArrayList<>();
        for (ClassDef c : new DexBackedDexFile(null, Files.readAllBytes(Paths.get(args[0]))).getClasses()) {
            if (selected.remove(c.getType())) { classes.add(c); continue; }
            List<Method> methods = new ArrayList<>(); List<Field> fields = new ArrayList<>();
            for (Method m : c.getMethods()) if (selected.remove(c.getType() + "->" + DexPatches.methodKey(m))) methods.add(m);
            for (Field f : c.getFields()) if (selected.remove(c.getType() + "->" + DexPatches.fieldKey(f))) fields.add(f);
            if (!methods.isEmpty() || !fields.isEmpty()) classes.add(DexPatches.members(c, fields, methods));
        }
        if (!selected.isEmpty()) throw new IllegalStateException("Missing overlay members: " + selected);
        DexPool.writeTo(args[2], new ImmutableDexFile(Opcodes.forApi(30), classes));
        System.out.println(args[2] + ": " + classes.size() + " class overlays");
    }
}
