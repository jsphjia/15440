#include <dlfcn.h>
#include <stdio.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <sys/socket.h>
#include <arpa/inet.h>
#include <netinet/in.h>
#include <fcntl.h>
#include <unistd.h>
#include <stdarg.h>
#include <string.h>
#include <err.h>
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <dirent.h>
#include "../include/dirtree.h"

#define MAXMSGLEN 10000
#define OFFSET 10000

// Struct definitions used to send data from client
typedef struct {
	int opcode;
	size_t size;
} rpc_header;

typedef struct {
	int path_length;
	int flags;
	mode_t mode;
} open_header;

typedef struct {
	int fd;
} close_header;

typedef struct {
	int fd;
	size_t count;
} read_header;

typedef struct {
	int fd;
	size_t count;
} write_header;

typedef struct {
	int fd;
	off_t offset;
	int whence;
} lseek_header;

typedef struct {
	int path_length;
} stat_header;

typedef struct {
	int path_length;
} unlink_header;

typedef struct {
	int fd;
	size_t nbytes;
	off_t offset;
} direntries_header;

typedef struct {
	int path_length;
} gettree_header;

typedef struct {
	int serialized_length;
} freetree_header;

// Enumeration of different operations to make casing easier
enum {
	OPEN,
	CLOSE,
	READ,
	WRITE,
	LSEEK,
	STAT,
	UNLINK,
	GETDIRENTRIES,
	GETDIRTREE,
	FREEDIRTREE
};

// Helper functions for each interposed file operation
int sOpen(int sessfd, open_header *open_head, char *path) { 
	int flags, fd;
	mode_t mode = open_head->mode;
	flags = open_head->flags;

	fd = open(path, flags, mode);
	return fd;
}

int sClose(int sessfd, close_header *close_head) { 
	int state = close(close_head->fd);
	return state;
}

ssize_t sRead(int sessfd, read_header *read_head, char *buffer) {
	ssize_t read_bytes = read(read_head->fd, (void*) buffer, read_head->count);
	return read_bytes;
}

ssize_t sWrite(int sessfd, int fd, size_t count, char* buf) {
	ssize_t written_bytes = write(fd, (void*)buf, count);
	return written_bytes;
}

off_t sLseek(int sessfd, lseek_header *lseek_head) {
	off_t new_offset = lseek(lseek_head->fd, lseek_head->offset, lseek_head->whence);
	return new_offset;
}

int sStat(int sessfd, struct stat* statbuf, const char *pathname) {
	int result = stat(pathname, statbuf);
	return result;
}

int sUnlink(int sessfd, const char *pathname) {
	int unlinked = unlink(pathname);
	return unlinked;
}

ssize_t sGetdirentries(int sessfd, direntries_header *dir_head, char *buf) {
	ssize_t nbytes_read = getdirentries(dir_head->fd, buf, dir_head->nbytes, &dir_head->offset);
	return nbytes_read;
}

struct dirtreenode *sGetdirtree(int sessfd, char *pathname) {
	struct dirtreenode *root = getdirtree(pathname);
	return root;
}

/*
 * Function: serialize_dirtree
 *
 * This function recursively serializes a directory tree to create a bytestream.
 *
 * Arguments:
 *   root - current node of the directory tree
 *   info - converted bytestream
 *   current_size - current size of info
 */
char *serialize_dirtree(struct dirtreenode *root, char **info, int *current_size) {
	int added_length = sizeof(int) + strlen(root->name) + 1 + sizeof(int);
	int name_length = strlen(root->name) + 1;
	size_t new_size = *current_size + added_length;
	int offset = *current_size;

	// increase allocated memory to store more subdirectories
	char *new_info = realloc(*info, new_size);
	if (new_info == NULL) {
		return NULL;
	}
	*info = new_info;

	// add current directory information
	memcpy(*info + offset, &name_length, sizeof(int));
	offset += sizeof(int);
	memcpy(*info + offset, root->name, name_length);
	offset += name_length;
	int subdirs = root->num_subdirs;
	memcpy(*info + offset, &subdirs, sizeof(int));
	
	*current_size = new_size;
	if(subdirs > 0) {
		// recursively iterate through all the subdirectories from left to right
		for (int i = 0; i < subdirs; i++) {
			serialize_dirtree(root->subdirs[i], info, current_size);
		}
	}
	else {
		root->subdirs = NULL;
	}
	return *info;
}

/*
 * Function: handle_clinet
 *
 * This function handles all requests sent by the client.
 *
 * Arguments:
 *   sessfd - session fd
 */
void handle_client(int sessfd) {
	int rv;
	int to_read;
	int offset, got;
	char *msg;
	char *buf;
	char *path;
	char *temp_buf;
	rpc_header *rpc_head = malloc(sizeof(rpc_header)); 

	// get messages and send replies to this client, until it goes away
	while ((rv = recv(sessfd, rpc_head, sizeof(rpc_header), 0)) > 0) {
		offset = 0;
		got = 0;
		size_t packet_size = rpc_head->size;
		buf = malloc(packet_size);

		// receive the rest of the packet
		while (got < packet_size) {
			if (packet_size - got > MAXMSGLEN) {
				to_read = MAXMSGLEN;
			}
			else to_read = packet_size - got;
			rv = recv(sessfd, buf + got, to_read, 0);
			got += rv;
			if (rv <= 0) break;
		}

		int op = rpc_head->opcode;

		switch (op) {
			case OPEN:
				// parse message from client
				fprintf(stderr, "open: received packet size [%ld]\n", rpc_head->size);
				open_header *open_head = malloc(sizeof(open_header));
				memcpy(open_head, buf + offset, sizeof(open_header));
				offset += sizeof(open_header);

				path = malloc(open_head->path_length);
				memcpy(path, buf + offset, open_head->path_length);

				int ofd = sOpen(sessfd, open_head, path);
				fprintf(stderr, "open: sent fd [%d] with errno [%d] for path [%s]\n", ofd, errno, path);

				// send message back to client
				msg = malloc(2 * sizeof(int)); 
				memcpy(msg, &ofd, sizeof(int));
				memcpy(msg + sizeof(int), &errno, sizeof(int));
				send(sessfd, msg, 2 * sizeof(int), 0);

				free(open_head);
				free(path);
				free(msg);
				break;

			case CLOSE:
				// parse message from client
				fprintf(stderr, "close: received packet size [%ld]\n", rpc_head->size);
				close_header *close_head = malloc(sizeof(close_header));
				memcpy(close_head, buf, sizeof(close_header));

				int state = sClose(sessfd, close_head);
				fprintf(stderr, "close: sent state [%d] with errno [%d]\n", state, errno);

				// send message back to client
				msg = malloc(2 * sizeof(int));
				memcpy(msg, &state, sizeof(int));
				memcpy(msg + sizeof(int), &errno, sizeof(int));
				send(sessfd, msg, 2 * sizeof(int), 0);

				free(close_head);
				free(msg);
				break;

			case READ:
				// parse message from client
				fprintf(stderr, "read: received packet size [%ld]\n", rpc_head->size);
				read_header *read_head = malloc(sizeof(read_header));
				memcpy(read_head, buf, sizeof(read_header));
				temp_buf = malloc(read_head->count);

				ssize_t bytes_read = sRead(sessfd, read_head, temp_buf);

				// send message back to client
				msg = malloc(sizeof(ssize_t) + sizeof(int) + bytes_read);
				memcpy(msg, &bytes_read, sizeof(ssize_t));
				memcpy(msg + sizeof(ssize_t), &errno, sizeof(int));
				memcpy(msg + sizeof(ssize_t) + sizeof(int), temp_buf, bytes_read);
				fprintf(stderr, "read: sent bytes read [%ld] with errno [%d]\n", bytes_read, errno);
				send(sessfd, msg, sizeof(ssize_t) + sizeof(int) + bytes_read, 0);

				free(read_head);
				free(temp_buf);
				free(msg);
				break;

			case WRITE:
				// parse message from client
				fprintf(stderr, "write: received packet size [%ld]\n", rpc_head->size);
				int fd;
				memcpy(&fd, buf, sizeof(int));
				offset += sizeof(int);

				size_t count;
				memcpy(&count, buf + offset, sizeof(size_t));
				offset += sizeof(size_t);

				temp_buf = malloc(count);
				memcpy(temp_buf, buf + offset, count);

				ssize_t bytes_written = sWrite(sessfd, fd, count, temp_buf);

				// send message back to client
				msg = malloc(sizeof(ssize_t) + sizeof(int));
				memcpy(msg, &bytes_written, sizeof(ssize_t));
				memcpy(msg + sizeof(ssize_t), &errno, sizeof(int));
				fprintf(stderr, "write: sent bytes written [%ld] with errno [%d]\n", bytes_written, errno);
				send(sessfd, msg, sizeof(ssize_t) + sizeof(int), 0);

				free(temp_buf);
				free(msg);
				break;

			case LSEEK:
				// parse message from client
				fprintf(stderr, "lseek: received packet size [%ld]\n", rpc_head->size);
				lseek_header *lseek_head = malloc(sizeof(lseek_header));
				memcpy(lseek_head, buf, sizeof(lseek_header));

				off_t location = sLseek(sessfd, lseek_head);
				
				// send message back to client
				msg = malloc(sizeof(off_t) + sizeof(int));
				memcpy(msg, &location, sizeof(off_t));
				memcpy(msg + sizeof(off_t), &errno, sizeof(int));
				fprintf(stderr, "lseek: sent location [%ld] with errno [%d]\n", location, errno);
				send(sessfd, msg, sizeof(off_t) + sizeof(int), 0);

				free(lseek_head);
				free(msg);
				break;

			case STAT:
				// parse message from client
				fprintf(stderr, "stat: received packet size [%ld]\n", rpc_head->size);
				stat_header *stat_head = malloc(sizeof(stat_header));
				memcpy(stat_head, buf, sizeof(stat_header));
				offset += sizeof(stat_header);

				path = malloc(stat_head->path_length);
				memcpy(path, buf + offset, stat_head->path_length);
				offset += stat_head->path_length;

				struct stat *statbuf = malloc(sizeof(struct stat));
				memcpy(statbuf, buf + offset, sizeof(struct stat));
				offset += sizeof(struct stat);

				fprintf(stderr, "checking path: %s\n", path);
				int result = sStat(sessfd, statbuf, path);

				// send message back to client
				msg = malloc(2 * sizeof(int));
				memcpy(msg, &result, sizeof(int));
				memcpy(msg + sizeof(int), &errno, sizeof(int));
				fprintf(stderr, "stat: sent result [%d] with errno [%d]\n", result, errno);
				send(sessfd, msg, 2 * sizeof(int), 0);

				free(stat_head);
				free(path);
				free(msg);
				break;

			case UNLINK:
				// parse message from client
				fprintf(stderr, "unlink: received packet size [%ld]\n", rpc_head->size);
				unlink_header *unlink_head = malloc(sizeof(unlink_header));
				memcpy(unlink_head, buf, sizeof(unlink_header));
				offset += sizeof(unlink_header);

				path = malloc(unlink_head->path_length);
				memcpy(path, buf + offset, unlink_head->path_length);

				int unlinked = sUnlink(sessfd, path);

				//send message back to client
				msg = malloc(2 * sizeof(int));
				memcpy(msg, &unlinked, sizeof(int));
				memcpy(msg + sizeof(int), &errno, sizeof(int));
				fprintf(stderr, "unlink: send unlinked [%d] with errno [%d]\n", unlinked, errno);
				send(sessfd, msg, 2 * sizeof(int), 0);

				free(unlink_head);
				free(path);
				free(msg);
				break;

			case GETDIRENTRIES:
				// parse message from client
				fprintf(stderr, "getdirentries: received packet size [%ld]\n", rpc_head->size);
				direntries_header *direntries_head = malloc(sizeof(direntries_head));
				memcpy(direntries_head, buf, sizeof(direntries_header));
				temp_buf = malloc(direntries_head->nbytes);

				ssize_t nbytes_read = sGetdirentries(sessfd, direntries_head, temp_buf);
				
				// send mesage back to client
				int response_length = sizeof(ssize_t) + sizeof(int) + nbytes_read + sizeof(off_t);
				msg = malloc(response_length);
				memcpy(msg, &nbytes_read, sizeof(ssize_t));
				offset += sizeof(ssize_t);

				memcpy(msg + offset, &errno, sizeof(int));
				offset += sizeof(int);

				memcpy(msg + offset, temp_buf, nbytes_read);
				offset += nbytes_read;

				off_t new_offset = direntries_head->offset;
				memcpy(msg + offset, &new_offset, sizeof(off_t));

				fprintf(stderr, "getdirentries: sent nbytes read [%ld] with offset [%ld] with errno [%d]\n", 
						nbytes_read, direntries_head->offset, errno);
				send(sessfd, msg, response_length, 0);

				free(direntries_head);
				free(temp_buf);
				free(msg);
				break;

			case GETDIRTREE:
				// parse message from client
				fprintf(stderr, "getdirtree: received packet size [%ld]\n", rpc_head->size);
				gettree_header *gettree_head = malloc(sizeof(gettree_header));
				memcpy(gettree_head, buf, sizeof(gettree_header));

				path = malloc(gettree_head->path_length);
				memcpy(path, buf + sizeof(gettree_header), gettree_head->path_length);

				struct dirtreenode *root = sGetdirtree(sessfd, path);
				char *serialized_tree = NULL;
				int *size = malloc(sizeof(int));
				*size = 0;
				serialized_tree = serialize_dirtree(root, &serialized_tree, size);
				fprintf(stderr, "length of serialized tree [%d]\n", *size);

				// send message back to client
				int return_length = 2 * sizeof(int) + *size;
				msg = malloc(return_length);
				memcpy(msg, &errno, sizeof(int));
				memcpy(msg + sizeof(int), size, sizeof(int));
				memcpy(msg + 2 * sizeof(int), serialized_tree, *size);
				fprintf(stderr, "getdirtree: sent serialized tree [%d] with errno [%d]\n", *size, errno);
				send(sessfd, msg, return_length, 0);

				free(gettree_head);
				free(path);
				free(size);
				free(msg);
				break;

			default:
				fprintf(stderr, "other opcode\n");
				msg = malloc(sizeof(int));
				memcpy(msg, &errno, sizeof(int));
				send(sessfd, msg, sizeof(int), 0);
				free(msg);
				break;
		}
	}
	// either client closed connection, or error
	if (rv < 0) err(1,0);
	free(rpc_head);
	close(sessfd);
}

int main(int argc, char**argv) {
	char *serverport;
	unsigned short port;
	int sockfd, sessfd, rv;
	struct sockaddr_in srv, cli;
	socklen_t sa_size;
	
	// Get environment variable indicating the port of the server
	serverport = getenv("serverport15440");
	if (serverport) port = (unsigned short) atoi(serverport);
	else port = 15440;
	
	// Create socket
	sockfd = socket(AF_INET, SOCK_STREAM, 0);	// TCP/IP socket
	if (sockfd < 0) err(1, 0);			// in case of error
	
	// setup address structure to indicate server port
	memset(&srv, 0, sizeof(srv));			// clear it first
	srv.sin_family = AF_INET;			// IP family
	srv.sin_addr.s_addr = htonl(INADDR_ANY);	// don't care IP address
	srv.sin_port = htons(port);			// server port

	// bind to our port
	rv = bind(sockfd, (struct sockaddr*)&srv, sizeof(struct sockaddr));
	if (rv < 0) err(1,0);
	
	// start listening for connections
	rv = listen(sockfd, 5);
	if (rv < 0) err(1,0);
	
	// main server loop, handle clients one at a time, quit after 10 clients
	while(1) {
		// wait for next client, get session socket
		sa_size = sizeof(struct sockaddr_in);
		sessfd = accept(sockfd, (struct sockaddr *)&cli, &sa_size);
		if (sessfd < 0) err(1, 0);
		if (fork() == 0) {
			close(sockfd);
			handle_client(sessfd);
			exit(0);
		}

	}
	// close socket
	close(sockfd);
	return 0;
}