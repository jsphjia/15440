// Imported Libraries
import java.io.*;
import java.util.*;
import java.rmi.*;
import java.util.concurrent.ConcurrentHashMap;

class Proxy {
	// Global Variables
	public static ConcurrentHashMap<Integer, RandomAccessFile> fd_files;
	public static ConcurrentHashMap<Integer, String> fd_paths;
	public static int curr_fd;

	// Additional Error Values
	public static final int EIO = -5;

	private static class FileHandler implements FileHandling {

		public synchronized int get_fd() {
			int fd = curr_fd;
			curr_fd ++;
			return fd;
		}

		// Function: open
		public int open (String path, OpenOption o) {
			int fd;
			File file;
			RandomAccessFile raf;
			switch (o) {
				case CREATE:
					try {
						file = new File(path);
						if (!file.exists()) {
							if (!file.createNewFile()) {
								System.err.printf("open (create): failed to create file for path [%s]\n", path);
								return Errors.EPERM;
							}
							System.err.printf("open (create): created file for path [%s]\n", path);
						}
						if (file.isDirectory()) {
							System.err.printf("open (create): file doesn't exist for path [%s]\n", path);
							return Errors.EISDIR;
						}
						file.setReadable(true, false);
						file.setWritable(true, false);
						fd = get_fd();
						raf = new RandomAccessFile(file, "rw");
						fd_files.put(fd, raf);
						fd_paths.put(fd, path);
						System.err.printf("open (create): fd sent [%d] for path [%s]\n", fd, path);
						return fd;
					} catch (IOException e) {
						System.err.println("open (create): error detected\n");
						return -5;
					}
				case CREATE_NEW:
					file = new File(path);
					if (file.exists()) {
						System.err.printf("open (create_new): file exists for path [%s]\n", path);
						return Errors.EEXIST;
					}
					if (file.isDirectory()) {
						System.err.printf("open (create_new): file doesn't exist for path [%s]\n", path);
						return Errors.EISDIR;
					}
					try {
						if (!file.createNewFile()) {
							System.err.printf("open (create_new): failed to create file for path [%s]\n", path);
							return Errors.EPERM;
						}
						file.setReadable(true, false); 
						file.setWritable(true, false); 
						fd = get_fd();
						raf = new RandomAccessFile(file, "rw");
						fd_files.put(fd, raf);
						fd_paths.put(fd, path);
						System.err.printf("open (create_new): fd sent [%d]\n", fd);
						return fd;
					} catch (IOException e) {
						System.err.println("open(create_new): error detected.\n");
						return -5;
					}
				case READ:
					file = new File(path);
					if (!file.exists()) {
						System.err.printf("open (read): file doesn't exist for path [%s]\n", path);
						return Errors.ENOENT;
					}
					file.setReadable(true, false); 
					file.setWritable(false, false);
					fd = get_fd();
					if (!file.isDirectory()) {
						try{
							raf = new RandomAccessFile(file, "r");
						} catch(IOException e) {
							return -5;
						}
						fd_files.put(fd, raf);
					}
					fd_paths.put(fd, path);
					System.err.printf("open (read): fd sent [%d] for path [%s]\n", fd, path);
					return fd;
				case WRITE:
					file = new File(path);
					if (!file.exists()) {
						System.err.printf("open (write): file doesn't exist for path [%s]\n", path);
						return Errors.ENOENT;
					}
					if (file.isDirectory()) {
						System.err.printf("open (write): path [%s] is a directory\n", path);
						return Errors.EISDIR;
					}
					file.setReadable(true, false); 
					file.setWritable(true, false);
					fd = get_fd();
					try{
						raf = new RandomAccessFile(file, "rw");
					} catch(IOException e) {
						return -5;
					}
					fd_files.put(fd, raf);
					fd_paths.put(fd, path);
					System.err.printf("open (write): fd sent [%d] for path [%s]\n", fd, path);
					return fd;
				default:
					System.err.println("open: invalid option given.\n");
					return Errors.EINVAL;
			}
		}

		public int close (int fd) {
			System.err.printf("close: received fd [%d]\n", fd);
			File file = new File(fd_paths.get(fd));
			if (!file.exists()) {
				System.err.printf("close: fd [%s] not found.\n", fd);
				return Errors.ENOENT;
			}
			if(file.isDirectory()) {
				fd_paths.remove(fd);
				System.err.printf("closed directory fd [%d]\n", fd);
				return 0;
			}
			try {
				RandomAccessFile close_raf = fd_files.get(fd);
				close_raf.close();
				fd_files.remove(fd);
				fd_paths.remove(fd);
				System.err.printf("closed file fd [%d]\n", fd);
				return 0;
			} catch (IOException e) {
				System.err.println("close: error detected.");
				return -5;
			}
		}

		public long write (int fd, byte[] buf) {
			System.err.printf("write: received fd [%d] and buf length [%d]\n", fd, buf.length);
			if (buf == null) {
				System.err.println("write: null buffer.");
				return Errors.EBADF;
			}
			if (!fd_paths.containsKey(fd)) {
				System.err.println("write: fd doesn't exist.");
				return Errors.EBADF;
			}
			File file = new File(fd_paths.get(fd));
			try {
				if (file.isDirectory()) {
					System.err.println("write: file is a directory.");
					return Errors.EISDIR;
				}
				if (!file.exists()) {
					System.err.println("write: file doesn't exist.");
					return Errors.EBADF;
				}
				if (!file.canWrite()) {
					System.err.println("write: file has no write permission.");
					return Errors.EBADF;
				}
			} catch (SecurityException s) {
				System.err.println("write: SecurityException error.");
				return Errors.EBADF;
			}

			RandomAccessFile write_raf = fd_files.get(fd);
			synchronized (write_raf) {
				try {
					System.err.printf("pos before: %d\n", write_raf.getFilePointer());
					write_raf.write(buf);
					System.err.printf("pos after: %d\n", write_raf.getFilePointer());
					System.err.printf("write: bytes written [%d]\n", buf.length);
					return (long) buf.length;
				} catch (IOException e) {
					System.err.println("write: IO error detected.");
					return -5;
				}
			}
		}

		public long read (int fd, byte[] buf) {
			System.err.printf("read: received fd [%d]\n", fd);
			if (!fd_paths.containsKey(fd)) {
				System.err.printf("read: fd [%d] doesn't exist.\n", fd);
				return Errors.EBADF;
			}
			File file = new File(fd_paths.get(fd));
			try {
				if (file.isDirectory()) {
					System.err.println("read: file is a directory.");
					return Errors.EISDIR;
				}
				if (!file.exists()) {
					System.err.println("read: file doesn't exist.");
					return Errors.ENOENT;
				}
				if (!file.canRead()) {
					System.err.println("read: file has no read permission.");
					return Errors.EBADF;
				}
			} catch (SecurityException s) {
				System.err.println("read: SecurityException error.");
				return Errors.EBADF;
			}

			RandomAccessFile read_raf = fd_files.get(fd);
			synchronized (read_raf) {
				try {
					long bytes_read = read_raf.read(buf);
					if (bytes_read == -1) {
						System.err.println("read: return byts read [0]");
						return 0;
					}
					System.err.printf("read: bytes read [%d]\n", bytes_read);
					return bytes_read;
				} catch (IOException e) {
					System.err.println("read: error detected.");
					System.err.println(e);
					return -5;
				}
			}
		}

		public long lseek (int fd, long pos, LseekOption o) {
			System.err.printf("lseek: received fd [%d]\n", fd);
			if (!fd_files.containsKey(fd)) {
				System.err.printf("lseek: fd [%d] doesn't exist.\n", fd);
				return Errors.EBADF;
			}
			File file = new File(fd_paths.get(fd));
			if (file.isDirectory()) {
				System.err.printf("lseek: fd [%d] is a directory\n", fd);
				return Errors.EBADF;
			}
			if (!file.exists()) {
				System.err.printf("lseek: fd [%d] doesn't exist\n", fd);
				return Errors.EBADF;
			}
			RandomAccessFile lseek_raf = fd_files.get(fd);
			synchronized (lseek_raf) {
				try {
					long new_pos;
					switch (o) {
						case FROM_CURRENT:
							new_pos = lseek_raf.getFilePointer() + pos;
							if (new_pos < 0) {
								System.err.println("lseek: invalid new offset from current.\n");
								return Errors.EINVAL;
							}
							System.err.printf("lseek (current): sent pos [%d]\n", new_pos);
							lseek_raf.seek(new_pos);
							return new_pos;
						case FROM_END:
							new_pos = lseek_raf.length() + pos;
							if (new_pos < 0) {
								System.err.println("lseek: invalid new offset from end.\n");
								return Errors.EINVAL;
							}
							System.err.printf("lseek (end): sent pos [%d]\n", new_pos);
							lseek_raf.seek(new_pos);
							return new_pos;
						case FROM_START:
							if (pos < 0) {
								System.err.println("lseek: invalid new offset from start.\n");
								return Errors.EINVAL;
							}
							System.err.printf("lseek (start): sent pos [%d]\n", pos);
							lseek_raf.seek(pos);
							return pos;
						default:
							System.err.println("lseek: invalid option given.\n");
							return Errors.EINVAL;
					}
				} catch (IOException e) {
					System.err.println("lseek: error detected.");
					return -5;
				}
			}
		}

		public int unlink (String path) {
			System.err.printf("unlink: received path [%s]\n", path);
			File file = new File(path);
			if (file.isDirectory() && file.list().length != 0) {
				System.err.printf("unlink: path [%s] is not an empty directory.\n", path);
				return Errors.ENOTEMPTY;
			}
			if (!file.exists()) {
				System.err.printf("unlink: file doesn't exist for path [%s].\n", path);
				return Errors.ENOENT;
			}
			synchronized (file) {
				if (!file.delete()) {
					System.err.printf("unlink: failed to delete file [%s].\n", path);
					return Errors.EPERM;
				}
				System.err.printf("unlink: successfully to deleted file [%s].\n", path);
				return 0;
			}
		}

		public void clientdone () {
			return;
		}
	}
	
	private static class FileHandlingFactory implements FileHandlingMaking {
		public FileHandling newclient () {
			return new FileHandler();
		}
	}

	public static void main (String[] args) throws IOException {
		fd_files = new ConcurrentHashMap<Integer, RandomAccessFile>();
		fd_paths = new ConcurrentHashMap<Integer, String>();
		curr_fd = 3;
		System.err.println("Starting!\n");
		(new RPCreceiver(new FileHandlingFactory())).run();
	}
}