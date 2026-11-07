import javax.microedition.midlet.*;
import javax.microedition.lcdui.*;

public class HelloMIDlet extends MIDlet implements CommandListener {
    private Display display;
    private Form form;
    private Command exitCommand;

    public void startApp() {
        display = Display.getDisplay(this);
        form = new Form("Hola J2ME");
        form.append("Compilado en Termux.");
        exitCommand = new Command("Salir", Command.EXIT, 1);
        form.addCommand(exitCommand);
        form.setCommandListener(this);
        display.setCurrent(form);
    }

    public void pauseApp() {}

    public void destroyApp(boolean unconditional) {}

    public void commandAction(Command c, Displayable d) {
        if (c == exitCommand) {
            destroyApp(false);
            notifyDestroyed();
        }
    }
}
