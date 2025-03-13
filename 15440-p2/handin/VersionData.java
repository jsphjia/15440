import java.io.*;

public class VersionData {
    public File file;
    public int version;
    public int num_clients;

    public VersionData (File f, int v, int n) {
        file = f;
        version = v;
        num_clients = n;
    }

    public void addClient () {
        num_clients++;
    }

    public void removeClient () {
        num_clients--;
    }

    public boolean isStale () {
        return num_clients == 0;
    }
}