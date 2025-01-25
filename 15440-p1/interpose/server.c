#include <stdio.h>
#include <stdlib.h>
#include <arpa/inet.h>
#include <netinet/in.h>
#include <sys/types.h>
#include <sys/socket.h>
#include <fcntl.h>
#include <string.h>
#include <unistd.h>
#include <err.h>
#include <errno.h>

#define MAXMSGLEN 100
#define OFFSET 100

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

int sOpen(int sessfd, open_header *open_head, char *path) { 
	int flags, fd;
	mode_t mode = open_head->mode;
	flags = open_head->flags;

	fd = open(path, flags, mode);
	fprintf(stderr, "opened path [%s] with fd [%d]\n", path, fd);
	return fd;
}

int sClose(int sessfd, close_header *close_head) { 
	int state = close(close_head->fd);
	return state;
}

ssize_t sRead(int sessfd, read_header *read_head, char *buffer) {
	ssize_t read_bytes = read(read_head->fd, (void*) buffer, read_head->count);
	if (errno) {
		return -1;
	}
	return read_bytes;
}

ssize_t sWrite(int sessfd, write_header *write_head, char* buf) {
	ssize_t written_bytes = write(write_head->fd, (void*)buf, write_head->count);
	// if (written_bytes < 0) {
	// 	return -1;
	// }
	return written_bytes;
}

void handle_client(int sessfd) {
	int rv;
	char *msg;
	char *buf;
	rpc_header *rpc_head = malloc(sizeof(rpc_header)); 

	// get messages and send replies to this client, until it goes away
	while ((rv = recv(sessfd, rpc_head, sizeof(rpc_header), 0)) > 0) {
		// fprintf(stderr, "new stuff\n");
		int offset = 0;
		// memcpy(rpc_head, buf, sizeof(rpc_header));
		int got = 0;
		buf = malloc(rpc_head->size);
		// fprintf(stderr, "here\n");
		while (got < rpc_head->size) {
			rv = recv(sessfd, buf, rpc_head->size, 0);
			got += rv;
			// fprintf(stderr, "received so far: %d\n", got);
			if (rv <= 0) break;
		}
		fprintf(stderr, "buf size [%ld], received bytes [%d]\n", rpc_head->size, rv);
		// offset += sizeof(rpc_header);
		int op = rpc_head->opcode;

		switch (op) {
			case OPEN:
				fprintf(stderr, "open\n");
				open_header *open_head = malloc(sizeof(open_header));
				memcpy(open_head, buf + offset, sizeof(open_header));
				offset += sizeof(open_header);

				char *pathname = malloc(open_head->path_length);
				memcpy(pathname, buf + offset, open_head->path_length);

				int ofd = sOpen(sessfd, open_head, pathname);
				fprintf(stderr, "open: sent fd [%d] with errno [%d]\n", ofd, errno);

				// send message back to client
				msg = malloc(2 * sizeof(int)); 
				sprintf(msg, "%d", ofd);
				sprintf(msg + sizeof(int), "%d", errno);
				send(sessfd, msg, 2 * sizeof(int), 0);
				free(open_head);
				free(pathname);
				break;
			case CLOSE:
				fprintf(stderr, "close\n");
				close_header *close_head = malloc(sizeof(close_header));
				memcpy(close_head, buf + sizeof(rpc_header), sizeof(close_header));
				int state = sClose(sessfd, close_head);
				fprintf(stderr, "close: sent state [%d] with errno [%d]\n", state, errno);

				// send message back to client
				msg = malloc(2 * sizeof(int));
				sprintf(msg, "%d", state);
				sprintf(msg + sizeof(int), "%d", errno);
				send(sessfd, msg, 2 * sizeof(int), 0);
				free(close_head);
				break;
			case READ:
				fprintf(stderr, "read\n");
				msg = "read";
				send(sessfd, msg, strlen(msg), 0);
				break;
				// fprintf(stderr, "read\n");
				// read_header *read_head = malloc(sizeof(read_header));
				// memcpy(read_head, buf + sizeof(rpc_header), sizeof(read_header));
				// fprintf(stderr, "read: trying to read [%ld] bytes from fd [%d]\n", read_head->count, read_head->fd);
				// buffer = malloc(read_head->count);
				// ssize_t bytes_read = read(read_head->fd, (void*) buffer, read_head->count);
				// if (errno) {
				// 	bytes_read = -1;
				// }
				// // ssize_t bytes_read = sRead(sessfd, read_head, buffer);
				// fprintf(stderr, "read: sent bytes read [%ld] with errno [%d]\n", bytes_read, errno);

				// // send message back to client
				// msg = malloc(sizeof(ssize_t) + sizeof(int) + bytes_read + 1);
				// sprintf(msg, "%ld", bytes_read);
				// offset += sizeof(ssize_t);
				// sprintf(msg + offset, "%d", errno); 
				// offset += sizeof(int);
				// memcpy(msg + offset, buffer, bytes_read + 1);
				// send(sessfd, msg, sizeof(ssize_t) + sizeof(int) + bytes_read + 1, 0);
				// free(read_head);
				// free(buffer);
				// break;
			case WRITE:
				fprintf(stderr, "write\n");
				write_header *write_head = malloc(sizeof(write_header));
				memcpy(write_head, buf, sizeof(write_header));
				offset += sizeof(write_header);

				char* temp_buf = malloc(write_head->count);
				// fprintf(stderr, "offset: %d\n", offset);
				memcpy(temp_buf, buf + offset, write_head->count);
				// fprintf(stderr, "contents: %s\n", temp_buf);
				fprintf(stderr, "received data: fd [%d] count [%ld]\n", write_head->fd, write_head->count);

				// buffer = malloc(write_head->count);
				// memcpy(buffer, buf + sizeof(rpc_header) + sizeof(write_header), write_head->count);
				ssize_t bytes_written = sWrite(sessfd, write_head, temp_buf);
				fprintf(stderr, "bytes written [%ld] to fd [%d]\n", bytes_written, write_head->fd);

				// send message back to client
				msg = malloc(sizeof(ssize_t) + sizeof(int));
				sprintf(msg, "%ld", bytes_written);
				sprintf(msg + sizeof(ssize_t), "%d", errno);
				send(sessfd, msg, sizeof(ssize_t) + sizeof(int), 0);
				break;
			case LSEEK:
				fprintf(stderr, "lseek\n");
				msg = "lseek";
				send(sessfd, msg, strlen(msg), 0);
				break;
			case STAT:
				fprintf(stderr, "stat\n");
				msg = "stat";
				send(sessfd, msg, strlen(msg), 0);
				break;
			case UNLINK:
				fprintf(stderr, "unlink\n");
				msg = "unlink";
				send(sessfd, msg, strlen(msg), 0);
				break;
			case GETDIRENTRIES:
				fprintf(stderr, "getdirentries\n");
				msg = "getdirentries";
				send(sessfd, msg, strlen(msg), 0);
				break;
			case GETDIRTREE:
				fprintf(stderr, "getdirtree\n");
				msg = "getdirtree";
				send(sessfd, msg, strlen(msg), 0);
				break;
			case FREEDIRTREE:
				fprintf(stderr, "freedirtree\n");
				msg = "freedirtree";
				send(sessfd, msg, strlen(msg), 0);
				break;
			default:
				fprintf(stderr, "other opcode\n");
				msg = "other";
				send(sessfd, msg, strlen(msg), 0);
				break;
		}
		// send reply
		// fprintf(stderr, "server replying to client: %s\n", msg);
		// send(sessfd, msg, strlen(msg), 0);	// should check return value
	}
	// either client closed connection, or error
	if (rv < 0) err(1,0);
	close(sessfd);
}

int main(int argc, char**argv) {
	// char *msg="Hello from server";
	// char buf[MAXMSGLEN+1];
	char *serverport;
	unsigned short port;
	int sockfd, sessfd, rv;
	struct sockaddr_in srv, cli;
	socklen_t sa_size;
	
	// Get environment variable indicating the port of the server
	// fprintf(stderr, "in server\n");
	serverport = getenv("serverport15440");
	if (serverport) port = (unsigned short) atoi(serverport);
	else port = 15440;
	
	// Create socket
	sockfd = socket(AF_INET, SOCK_STREAM, 0);	// TCP/IP socket
	if (sockfd < 0) err(1, 0);			// in case of error
	// fprintf(stderr, "socket created\n");
	
	// setup address structure to indicate server port
	memset(&srv, 0, sizeof(srv));			// clear it first
	srv.sin_family = AF_INET;			// IP family
	srv.sin_addr.s_addr = htonl(INADDR_ANY);	// don't care IP address
	srv.sin_port = htons(port);			// server port
	// fprintf(stderr, "address structure setup\n");

	// bind to our port
	rv = bind(sockfd, (struct sockaddr*)&srv, sizeof(struct sockaddr));
	// fprintf(stderr, "bind rv: %d\n", rv);
	if (rv < 0) err(1,0);
	// fprintf(stderr, "after bind\n");
	
	// start listening for connections
	rv = listen(sockfd, 5);
	if (rv < 0) err(1,0);
	// fprintf(stderr, "after listen\n");
	
	// main server loop, handle clients one at a time, quit after 10 clients
	while(1) {
		// wait for next client, get session socket
		sa_size = sizeof(struct sockaddr_in);
		// fprintf(stderr, "before accept\n");
		sessfd = accept(sockfd, (struct sockaddr *)&cli, &sa_size);
		if (sessfd < 0) err(1, 0);
		// fprintf(stderr, "after accept\n");
		if (fork() == 0) {
			close(sockfd);
			handle_client(sessfd);
			exit(0);
		}
		// close(sessfd);

	}
	
	// fprintf(stderr, "server shutting down cleanly\n");
	// close socket
	close(sockfd);

	return 0;
}

