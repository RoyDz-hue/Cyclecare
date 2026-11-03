public class Floats {
    public static void main(String[] a) {
        // Deterministic sweep: integers, common decimals, temps, sleep,
        // powers, and a fixed LCG over raw f32 bit patterns in plain range.
        StringBuilder sb = new StringBuilder();
        for (int i = -2000; i <= 2000; i++) sb.append(Float.toString((float) i)).append('\n');
        for (int i = 0; i <= 10000; i++) sb.append(Float.toString(i / 100f)).append('\n');
        for (int i = 3400; i <= 4200; i++) sb.append(Float.toString(i / 100f)).append('\n');
        for (int i = 0; i <= 96; i++) sb.append(Float.toString(i / 4f)).append('\n');
        for (int i = 1; i <= 1000; i++) sb.append(Float.toString(i / 1000f)).append('\n');
        long s = 0x12345678L;
        for (int i = 0; i < 1000000; i++) {
            s = (s * 6364136223846793005L + 1442695040888963407L);
            float f = Float.intBitsToFloat((int) (s >>> 32));
            float abs = Math.abs(f);
            if (Float.isNaN(f) || Float.isInfinite(f) || abs == 0f || abs < 1e-3f || abs >= 1e7f) continue;
            sb.append(Float.toString(f)).append('\n');
        }
        System.out.print(sb);
    }
}
