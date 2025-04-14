import java.io.Serializable;

public class Message implements Serializable {
    public String filename;
    public byte[] img;
    public String[] sources;
    public mType type;
    public boolean vote;

    public enum mType {
        PREPARE,
        REPLY,
        DECISION,
        ACK
    }

    public Message (String filename, byte[] img, String[] sources, mType type, boolean vote) {
        this.filename = filename;
        this.img = img;
        this.sources = sources;
        this.type = type;
        this.vote = vote;
    }
}
