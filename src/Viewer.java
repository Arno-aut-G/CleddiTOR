import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Structure;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

public class Viewer {
    private static final int ARROW_UP = 1000,
    ARROW_DOWN = 1001,
    ARROW_RIGHT = 1002,
    ARROW_LEFT = 1003,
    HOME = 1004,
    END = 1005, PAGE_UP = 1006, PAGE_DOWN = 1007, DEL = 1008;
    private static LibC.Termios originalAttributes;
    private static int rows;
    private static int columns;
    private static int cursorX = 0, cursorY = 0, offsetY = 0, offsetX = 0;
    private static List<String> content = List.of();

    public static void main(String[] args) throws IOException {
        openFile(args);

//        TODO
//        Page up/down
//        Horizontal scrolling

        openFile(args);
        enableRawMode();
        initEditor();

        while (true) {
            scroll();
            refreshScreen();
            int key = readKey();
            handleKey(key);
        }
    }

//    TODO Think about this implementation again (why, e.g. the +1?)
    private static void scroll() {
        if (cursorY >= rows + offsetY) {
            offsetY = cursorY - rows + 1;
        } else if (cursorY < offsetY) {
            offsetY = cursorY;
        }
    }

    private static void openFile(String[] args) {
        if (args.length == 1) {
            Path filePath = Path.of(args[0]);
            if (Files.exists(filePath)) {
                try (Stream<String> stringStream = Files.lines(filePath)) {
                    content = stringStream.toList();
                } catch (IOException e) {
//                    TODO Show message in statusbar!
                }
            }
        }
    }

    private static void initEditor() {
        LibC.Winsize windowSize = getWindowSize();
        rows = windowSize.ws_row - 1;
        columns = windowSize.ws_col;
    }

    private static void refreshScreen() {
        StringBuilder builder = new StringBuilder();

        moveCursorToTopLeft(builder);
        drawContent(builder);
        drawStatusBar(builder);
        drawCursor(builder);
        System.out.print(builder);
    }

    private static void moveCursorToTopLeft(StringBuilder builder) {
        builder
                .append("\033[H");
    }

    private static void drawCursor(StringBuilder builder) {
        builder.append(String.format("\033[%d;%dH", cursorY - offsetY + 1, cursorX + 1));
    }

    private static void drawStatusBar(StringBuilder builder) {
        String statusmessage = "Rows: " + rows + " X: " + cursorX + " Y: " + cursorY;
        builder
                .append("\033[7m")
                .append(statusmessage)
                .append(" ".repeat(Math.max(0, columns - statusmessage.length())))
                .append("\033[0m");
    }

    private static void drawContent(StringBuilder builder) {
        for (int i = 0; i < rows; i++) {
            int fileI = offsetY + i;
            if (fileI >= content.size()) {
                builder.append("~");
            } else {
                builder.append(content.get(fileI));
            }
            builder.append("\033[K\r\n");
        }
    }

    private static void handleKey(int key) {
        if (key == '\021') {
            exit();
        } else if (List.of(ARROW_UP, ARROW_DOWN, ARROW_LEFT, ARROW_RIGHT, HOME, END).contains(key)) {
            moveCursor(key);
        }
//        else {
//        System.out.println((char) key + " (" + key + ")\r");
//        }
    }

    private static void moveCursor(int key) {
        switch (key) {
            case ARROW_UP -> {
                if (cursorY > 0) {
                    cursorY--;
                }
            }
            case ARROW_DOWN -> {
                if (cursorY < content.size()) {
                    cursorY++;
                }
            }
            case ARROW_LEFT -> {
                if (cursorX > 0) {
                    cursorX--;
                }
            }
            case ARROW_RIGHT -> {
                if (cursorX < columns - 1) {
                    cursorX++;
                }
            }
            case HOME -> cursorX = 0;

            case END -> cursorX = columns - 1;

        }
    }

    private static void exit() {
        System.out.print("\033[2J");
        System.out.print("\033[H");
        LibC.INSTANCE.tcsetattr(LibC.SYSTEM_OUT_FD, LibC.TCSAFLUSH, originalAttributes);
        System.exit(0);
    }

    private static int readKey() throws IOException {
        int key = System.in.read();
        if (key != '\033') {
            return key;
        }

        int nextKey = System.in.read();
        if (nextKey != '[' && nextKey != 'O') {
            return nextKey;
        }

        int yetAnotherKey = System.in.read();

        if (nextKey == '[') {
            return switch (yetAnotherKey) {
                case 'A' -> ARROW_UP;
                case 'B' -> ARROW_DOWN;
                case 'C' -> ARROW_RIGHT;
                case 'D' -> ARROW_LEFT;
                case 'H' -> HOME;
                case 'F' -> END;
                case '0', '1', '2', '3', '4', '5', '6', '7', '8', '9' -> {  // e.g: esc[5~ == page_up
                    int yetYetAnotherChar = System.in.read();
                    if (yetYetAnotherChar != '~') {
                        yield yetYetAnotherChar;
                    }
                    switch (yetAnotherKey) {
                        case '1':
                        case '7':
                            yield HOME;
                        case '3':
                            yield DEL;
                        case '4':
                        case '8':
                            yield END;
                        case '5':
                            yield PAGE_UP;
                        case '6':
                            yield PAGE_DOWN;
                        default:
                            yield yetAnotherKey;
                    }
                }
                default -> yetAnotherKey;
            };
        } else  { //if (nextKey == 'O') {  e.g. escpOH == HOME
            return switch (yetAnotherKey) {
                case 'H' -> HOME;
                case 'F' -> END;
                default -> yetAnotherKey;
            };
        }
    }

    private static void enableRawMode() {
        LibC.Termios termios = new LibC.Termios();
        int rc = LibC.INSTANCE.tcgetattr(LibC.SYSTEM_OUT_FD, termios);
        originalAttributes = LibC.Termios.of(termios);

        if (rc != 0) {
            System.err.println("There was a problem tcgetattr.");
            System.exit(rc);
        }


        termios.c_lflag &= ~(LibC.ECHO | LibC.ICANON | LibC.IEXTEN | LibC.ISIG);
        termios.c_iflag &= ~(LibC.IXON | LibC.ICRNL);
        termios.c_oflag &= ~(LibC.OPOST);

        LibC.INSTANCE.tcsetattr(LibC.SYSTEM_OUT_FD, LibC.TCSAFLUSH, termios);
    }

    private static LibC.Winsize getWindowSize() {
        final LibC.Winsize winsize = new LibC.Winsize();
        final int rc = LibC.INSTANCE.ioctl(LibC.SYSTEM_OUT_FD, LibC.TIOCGWINSZ, winsize);

        if (rc != 0) {
            System.err.println("ioctl failed with return code" + rc);
            System.exit(1);
        }
        return winsize;
    }

    interface LibC extends Library {
        int SYSTEM_OUT_FD = 0;
        int ISIG = 1, ICANON = 2, ECHO = 10, TCSAFLUSH = 2,
                IXON = 2000, ICRNL = 400, IEXTEN = 100000, OPOST = 1, VMIN = 6, VTIME = 5, TIOCGWINSZ = 0x5413;

        // we're loading the C standard library for POSIX systems
        LibC INSTANCE = Native.load("c", LibC.class);

        @Structure.FieldOrder(value = {"ws_row", "ws_col", "ws_xpixel", "ws_ypixel"})
        class Winsize extends Structure {
            public short ws_row, ws_col, ws_xpixel, ws_ypixel;
        }
        @Structure.FieldOrder(value = {"c_iflag", "c_oflag", "c_cflag", "c_lflag", "c_cc"})
        class Termios extends Structure {
            public int c_iflag, c_oflag, c_cflag, c_lflag;
            public byte[] c_cc = new byte[19];   /* special characters */

            public Termios() {

            }

            public static Termios of(Termios t) {
                Termios copy = new Termios();
                copy.c_iflag = t.c_iflag;
                copy.c_oflag = t.c_oflag;
                copy.c_cflag = t.c_cflag;
                copy.c_lflag = t.c_lflag;
                copy.c_cc = t.c_cc;
                return copy;
            }

            @Override
            public String toString() {
                return "Termios{" +
                        "c_iflag=" + c_iflag +
                        ", c_oflag=" + c_oflag +
                        ", c_cflag=" + c_cflag +
                        ", c_lflag=" + c_lflag +
                        ", c_cc=" + Arrays.toString(c_cc) +
                        '}';
            }
        }

        int tcgetattr(int fd, Termios termios);

        int tcsetattr(int fd, int optional_actions,
                     Termios termios);

        int ioctl(int fd, int opt, Winsize winsize);

    }
}