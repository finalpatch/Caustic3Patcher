import com.singlecellsoftware.caustic.midi.MidiStreamParser;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class MidiStreamParserTest {
    private static byte[] bytes(int... values) {
        byte[] result = new byte[values.length];
        for (int i = 0; i < values.length; i++) result[i] = (byte) values[i];
        return result;
    }
    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte b : bytes) result.append(String.format("%02x", b & 255));
        return result.toString();
    }
    private static int checks;
    private static void check(String name, List<String> actual, String... expected) {
        if (!actual.equals(Arrays.asList(expected)))
            throw new AssertionError(name + ": " + actual + " != " + Arrays.toString(expected));
        checks++;
    }
    public static void main(String[] args) {
        byte[] stream = bytes(0x90,60,100,61,0,0xc2,7,8,0xe3,0,64,0xb4,1,127,0x80,60,0);
        String[] expected = {"09903c64","09903d00","0cc20700","0cc20800","0ee30040","0bb4017f","08803c00"};
        for (int split = 0; split <= stream.length; split++) {
            List<String> out = new ArrayList<>();
            MidiStreamParser parser = new MidiStreamParser(p -> out.add(hex(p)));
            parser.accept(stream,0,split);
            parser.accept(stream,split,stream.length-split);
            check("split " + split,out,expected);
        }
        List<String> out = new ArrayList<>();
        MidiStreamParser parser = new MidiStreamParser(p -> out.add(hex(p)));
        for (byte b : stream) parser.accept(new byte[]{b},0,1);
        check("single-byte chunks",out,expected);
        out.clear(); parser.reset();
        byte[] realtime = bytes(0x90,60,0xf8,0xfa,100,61,0xfc,0);
        parser.accept(realtime,0,realtime.length);
        check("interleaved realtime",out,"0ffa0000","09903c64","0ffc0000","09903d00");
        out.clear(); parser.reset();
        byte[] unsupported = bytes(0x90,60,0xf0,1,2,0xfa,3,0xf7,64,100,0xf2,1,2,60,100,0xa1,60,1,0xd1,80,0x91,62,99);
        parser.accept(unsupported,0,unsupported.length);
        check("SysEx/common/aftertouch isolation",out,"0ffa0000","09913e63");
        out.clear(); parser.reset();
        byte[] truncated = bytes(0x90,60,0x80,60,0,0xf0,1,0x92,70,80);
        parser.accept(truncated,0,truncated.length);
        check("new status resynchronizes",out,"08803c00","09924650");
        out.clear(); parser.reset();
        parser.accept(bytes(0xff,0x90,64,127,0xfe),1,3);
        check("offset excludes surrounding bytes",out,"0990407f");
        out.clear(); parser.reset();
        parser.accept(bytes(0x90,60),0,2); parser.reset();
        parser.accept(bytes(100,61,100),0,3);
        check("reset removes partial and running status",out);
        int[][] invalid = {{-1,1},{0,-1},{1,2},{Integer.MAX_VALUE,1},{0,Integer.MAX_VALUE}};
        for (int[] range : invalid) {
            try { parser.accept(new byte[2],range[0],range[1]); throw new AssertionError("bad bounds accepted"); }
            catch (IllegalArgumentException expectedError) { checks++; }
        }
        try { parser.accept(null,0,0); throw new AssertionError("null accepted"); }
        catch (IllegalArgumentException expectedError) { checks++; }
        // Every channel, supported status and data boundary must stay packet aligned.
        for (int channel=0; channel<16; channel++) for (int type : new int[]{8,9,11,12,14}) {
            out.clear(); parser.reset();
            byte[] input = bytes((type<<4)|channel,127,0);
            parser.accept(input,0,type==12?2:3);
            check("channel/type",out,hex(bytes(type,(type<<4)|channel,127,0)));
        }
        System.out.println("PASS: " + checks + " MIDI framing checks");
    }
}
