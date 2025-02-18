// Imported Libraries
import java.io.*;
import java.nio.file.OpenOption;
import java.util.*;
import java.rmi.*;
import java.rmi.registry.Registry;
import java.rmi.registry.LocateRegistry;
import java.util.concurrent.ConcurrentHashMap;

class Proxy {
	// Global Variables
	public static ConcurrentHashMap<Integer, RandomAccessFile> fd_files;
	public static ConcurrentHashMap<Integer, String> fd_paths;
	public static int curr_fd;
	public static String hostIP;
	public static int port;
	public static RMIInterface stub;

	// Cache Variables
	public static Cache cache;
	public static int max_size;
	public static String cache_dir;

	// Additional Error Values
	public static final int EIO = -5;

	// Storing file info
	public static class fileInfo{
		boolean isDirectory;
        int length;
        byte[] info;
    }

	private static class FileHandler implements FileHandling {

		public synchronized int getFd() {
			int fd = curr_fd;
			curr_fd ++;
			return fd;
		}

		// Function: open
		public int open (String path, OpenOption o) {
			int fd;
			File file, fd_file;
			String fd_path, cache_path;
			RandomAccessFile raf, fd_raf, tmp_raf;
			byte[] buf;
			switch (o) {
				case CREATE:
					try {
						System.err.printf("open (create): file [%s]\n", path);
						cache_path = cache_dir + "/" + path;

						// check if file exists on the cache already
						// implies the file already exists on server
						if (cache.lru_cache.containsKey(cache_path)) {
							System.err.printf("open (create): file [%s] in cache\n", cache_path);
							file = cache.lru_cache.get(cache_path);

							// update file from server
							// buf = stub.getFileInfo(path);
							tmp_raf = new RandomAccessFile(file, "rw");
							tmp_raf.seek(0);
							buf = new byte[(int) tmp_raf.length()];
							tmp_raf.read(buf);
							tmp_raf.seek(0);

						}
						// file doesn't exist in the cache
						else {
							// check if file exists on server
							if (!stub.serverExists(path)) {
								stub.createFile(path);
								System.err.printf("open (create): server created file [%s]\n", path);
							}
							System.err.printf("open (create): file exists on server [%b]\n", stub.serverExists(path));

							// check if file exists on proxy
							file = new File(cache_path);
							if (!file.exists()) {
								file.createNewFile();
							}
							System.err.printf("open (create): file exists on proxy [%b]\n", file.exists());

							// copy over contents from server copy to proxy copy
							buf = stub.getFileInfo(path);
							tmp_raf = new RandomAccessFile(file, "rw");
							tmp_raf.seek(0);
							tmp_raf.write(buf);

							cache.lru_cache.put(cache_path, file);
							System.err.printf("open (create): file exists in cache [%b]\n", cache.lru_cache.containsKey(cache_path));
						}

						// create fd copy
						fd = getFd();
						fd_path = path + "-fd" + fd;
						fd_file = new File(fd_path);
						fd_file.createNewFile();
						fd_file.setReadable(true, false);
						fd_file.setWritable(true, false);

						// copy over contents from proxy copy to fd copy
						fd_raf = new RandomAccessFile(fd_file, "rw");
						fd_raf.seek(0);
						fd_raf.write(buf);
						fd_raf.seek(0);

						// update all hash maps
						fd_paths.put(fd, path);
						fd_files.put(fd, fd_raf);
						System.err.printf("open (create): fd [%d] for path [%s]\n", fd, fd_path);
						return fd;
					} catch (IOException e) {
						System.err.printf("open (create): error [%s]", e.toString());
						return EIO;
					}
				case CREATE_NEW:
					try {
						System.err.printf("open (create_new): file [%s]\n", path);
						cache_path = cache_dir + "/" + path;

						// check if file exists on the cache already
						// implies the file already exists on server
						if (cache.lru_cache.containsKey(cache_path)) {
							System.err.printf("open (create_new): file [%s] in cache\n", cache_path);
							return Errors.EEXIST;
						}
						// file doesn't exist in the cache
						else {
							// check if file exists on server
							if (stub.serverExists(path)) {
								System.err.printf("open (create_new): file [%s] exists on server\n", path);
								return Errors.EEXIST;
							}

							// check if file exists on proxy
							file = new File(cache_path);
							if (file.exists()) {
								System.err.printf("open (create_new): file [%s] exists on proxy\n", path);
								return Errors.EEXIST;
							}
							stub.createFile(path);
							file.createNewFile();
							System.err.printf("open (create_new): file exists on proxy [%b]\n", file.exists());

							// copy over contents from server copy to proxy copy
							buf = stub.getFileInfo(path);
							tmp_raf = new RandomAccessFile(file, "rw");
							tmp_raf.seek(0);
							tmp_raf.write(buf);

							cache.lru_cache.put(cache_path, file);
							System.err.printf("open (create_new): file exists in cache [%b]\n", cache.lru_cache.containsKey(cache_path));
						}

						// create fd copy
						fd = getFd();
						fd_path = path + "-fd" + fd;
						fd_file = new File(fd_path);
						fd_file.createNewFile();
						fd_file.setReadable(true, false);
						fd_file.setWritable(true, false);

						// copy over contents from proxy copy to fd copy
						fd_raf = new RandomAccessFile(fd_file, "rw");
						fd_raf.seek(0);
						fd_raf.write(buf);
						fd_raf.seek(0);

						// update all hash maps
						fd_paths.put(fd, path);
						fd_files.put(fd, fd_raf);
						System.err.printf("open (create_new): fd [%d] for path [%s]\n", fd, fd_path);
						return fd;
					} catch (IOException e) {
						System.err.printf("open (create_new): error [%s]", e.toString());
						return EIO;
					}
				case READ:
					try{
						System.err.printf("open (read): file [%s]\n", path);
						cache_path = cache_dir + "/" + path;

						// check if file exists on the cache already
						// implies the file already exists on server
						if (cache.lru_cache.containsKey(cache_path)) {
							System.err.printf("open (read): file [%s] in cache\n", cache_path);
							file = cache.lru_cache.get(cache_path);

							// update file from server
							// buf = stub.getFileInfo(path);
							// System.err.printf("open (read): buf length [%d]\n", buf.length);
							tmp_raf = new RandomAccessFile(file, "rw");
							tmp_raf.seek(0);
							buf = new byte[(int) tmp_raf.length()];
							tmp_raf.read(buf);
							tmp_raf.seek(0);
						}
						else {
							// check if file exists on server
							if (!stub.serverExists(path)) {
								System.err.printf("open (read): file [%s] not on server\n", path);
								return Errors.ENOENT;
							}

							// check if file exists on proxy
							file = new File(cache_path);
							if (!file.exists()) {
								file.createNewFile();
							}
							System.err.printf("open (read): file exists on proxy [%b]\n", file.exists());

							// copy over contents from server copy to proxy copy
							buf = stub.getFileInfo(path);
							tmp_raf = new RandomAccessFile(file, "rw");
							tmp_raf.seek(0);
							tmp_raf.write(buf);

							cache.lru_cache.put(cache_path, file);
							System.err.printf("open (read): file exists in cache [%b]\n", cache.lru_cache.containsKey(cache_path));
						}

						if (!file.isDirectory()) {
							// create fd copy
							fd = getFd();
							fd_path = path + "-fd" + fd;
							fd_file = new File(fd_path);
							fd_file.createNewFile();
							fd_file.setReadable(true, false);
							fd_file.setWritable(true, false);

							// copy over contents from proxy copy to fd copy
							fd_raf = new RandomAccessFile(fd_file, "rw");
							fd_raf.seek(0);
							fd_raf.write(buf);
							fd_raf.seek(0);
							fd_file.setWritable(false, false);

							// update all hash maps
							fd_paths.put(fd, path);
							fd_files.put(fd, fd_raf);
							System.err.printf("open (read): fd [%d] for path [%s]\n", fd, fd_path);
						}
						else {
							fd = getFd();
							fd_path = path + "-fd" + fd;
							fd_file = new File(fd_path);
							fd_file.createNewFile();
							fd_file.setReadable(true, false);
							fd_file.setWritable(false, false);
							fd_paths.put(fd, path);
							System.err.printf("open (read): fd [%d] for direcory [%s]\n", fd, fd_path);
						}
						return fd;
					} catch (IOException e) {
						System.err.printf("open (read): error [%s]", e.toString());
						return EIO;
					}
				case WRITE:
				try{
					System.err.printf("open (write): file [%s]\n", path);
					cache_path = cache_dir + "/" + path;

					// check if file exists on the cache already
					// implies the file already exists on server
					if (cache.lru_cache.containsKey(cache_path)) {
						System.err.printf("open (write): file [%s] in cache\n", cache_path);
						file = cache.lru_cache.get(cache_path);

						// update file from server
						// buf = stub.getFileInfo(path);
						tmp_raf = new RandomAccessFile(file, "rw");
						tmp_raf.seek(0);
						buf = new byte[(int) tmp_raf.length()];
						tmp_raf.read(buf);
						tmp_raf.seek(0);
					}
					else {
						// check if file exists on server
						if (!stub.serverExists(path)) {
							System.err.printf("open (write): file [%s] not on server\n", path);
							return Errors.ENOENT;
						}

						// check if file exists on proxy
						file = new File(cache_path);
						if (file.isDirectory()) {
							System.err.printf("open (write): file [%s] is a directory\n", cache_path);
							return Errors.EISDIR;
						}
						if (!file.exists()) {
							file.createNewFile();
						}
						System.err.printf("open (write): file exists on proxy [%b]\n", file.exists());

						// copy over contents from server copy to proxy copy
						buf = stub.getFileInfo(path);
						tmp_raf = new RandomAccessFile(file, "rw");
						tmp_raf.seek(0);
						tmp_raf.write(buf);

						cache.lru_cache.put(cache_path, file);
						System.err.printf("open (write): file exists in cache [%b]\n", cache.lru_cache.containsKey(cache_path));
					}

					// create fd copy
					fd = getFd();
					fd_path = path + "-fd" + fd;
					fd_file = new File(fd_path);
					fd_file.createNewFile();
					fd_file.setReadable(true, false);
					fd_file.setWritable(true, false);

					// copy over contents from proxy copy to fd copy
					fd_raf = new RandomAccessFile(fd_file, "rw");
					fd_raf.seek(0);
					fd_raf.write(buf);
					fd_raf.seek(0);

					// update all hash maps
					fd_paths.put(fd, path);
					fd_files.put(fd, fd_raf);
					System.err.printf("open (write): fd [%d] for path [%s]\n", fd, fd_path);
					return fd;
				} catch (IOException e) {
					System.err.printf("open (write): error [%s]", e.toString());
					return EIO;
				}
				default:
					System.err.println("open: invalid option given.\n");
					return Errors.EINVAL;
			}
		}

		public int close (int fd) {
			System.err.printf("close: received fd [%d]\n", fd);
			String path = fd_paths.get(fd);
			String fd_path = path + "-fd" + fd;
			File file = new File(fd_path);
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
				if (file.canWrite()) {
					String cache_path = cache_dir + "/" + path;
					File cache_file = new File(cache_path);
					RandomAccessFile raf = new RandomAccessFile(cache_file, "rw");
					byte[] buf = new byte[(int) raf.length()];
					raf.seek(0);
					raf.write(buf);
					buf = new byte[(int) close_raf.length()];
					close_raf.seek(0);
					close_raf.read(buf);
					stub.updateFile(path, buf); // update server
					raf.seek(0);
					raf.write(buf); // update proxy version
				}
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
			String fd_path = fd_paths.get(fd) + "-fd" + fd;
			File file = new File(fd_path);
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
			String fd_path = fd_paths.get(fd) + "-fd" + fd;
			File file = new File(fd_path);
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
						System.err.println("read: return bytes read [0]");
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
			String fd_path = fd_paths.get(fd) + "-fd" + fd;
			File file = new File(fd_path);
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

		hostIP = args[0];
		port = Integer.parseInt(args[1]);
		cache_dir = args[2];
		max_size = Integer.parseInt(args[3]);
		cache = new Cache(max_size);
		curr_fd = 3;

		try {
			Registry registry = LocateRegistry.getRegistry(hostIP, port);
			stub = (RMIInterface) registry.lookup("RMIInterface");
			String response = stub.sayHello("MyFancyClient");
			System.err.println("Reponse: " + response);
		} catch (RemoteException e) {
			System.err.println("Unable to locate registry or unable to call RPC sayHello");
			e.printStackTrace();
			System.exit(1);
		} catch (NotBoundException e) {
			System.err.println("MyFancyInterface not found");
			e.printStackTrace();
			System.exit(1);
		}

		System.err.println("Starting!\n");
		(new RPCreceiver(new FileHandlingFactory())).run();
	}
}