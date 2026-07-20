package quickdiscscan;

public final class I18nTest {
    public static void main(String[] args) {
        String actual = I18n.text("Deutsch", "English");
        if (args.length != 1 || !actual.equals(args[0])) {
            throw new AssertionError("Erwartet " + java.util.Arrays.toString(args) + ", erhalten " + actual);
        }
    }
}
