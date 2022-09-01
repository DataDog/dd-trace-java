#!/usr/bin/env python3
# -*- coding: utf-8 -*-
import argparse
import errno
import json
import logging
import os
import re
import requests
import requests_unixsocket
import signal
import socket
import ssl
import subprocess
import sys
import threading
import time
import urllib
from functools import partial
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


def parse_args():
  parser = argparse.ArgumentParser(description='Simple TCP proxy for Docker')
  parser.add_argument('-p', '--port', dest='local_port', type=int, default=8080, help='Local port of the proxy')
  parser.add_argument('-r', '--remote', dest='remote',
                      help='Remote IP or host name (http://0.0.0.0:2375, unix:///var/run/docker.sock)')
  parser.add_argument('-f', '--forward', dest='forward', default='remote-docker',
                      help='Remote IP or host name to forward connections')
  parser.add_argument('--secure', dest='secure', action='store_true')
  parser.set_defaults(secure=False)
  parser.add_argument('-sk', '--server-key', dest='server_key', help='Key for the local server endpoint')
  parser.add_argument('-sc', '--server-cert', dest='server_cert', help='Certificate for the local server endpoint')
  parser.add_argument('-rk', '--remote-key', dest='remote_key', help='Key for the remote endpoint')
  parser.add_argument('-rc', '--remote-cert', dest='remote_cert', help='Certificate remote endpoint')
  parser.add_argument('-rca', '--remote-ca', dest='remote_ca', help='CA for the remote endpoint')
  parser.add_argument('--verbose', dest='verbose', action='store_true')
  parser.set_defaults(verbose=False)
  return parser.parse_args()


class TcpUpgrade:
  """
  Docker remote API defines what is called a connection Hijacking via "Connection: upgrade" and "Upgrade: tcp" headers
  This class tries to detect a tcp upgrade handshake and keep the sockets open until the remote docker API endpoint
  decides to close the connection
  """

  def __init__(self, proxy_server, timeout=2, buffer_size=1024, encoding='utf-8'):
    self.proxy_server = proxy_server
    self.args = self.proxy_server.args
    self.remote = self.proxy_server.remote
    self.encoding = encoding
    self.timeout = timeout
    self.buffer_size = buffer_size

  def try_tcp_upgrade(self, local_socket, request_line, headers, body):
    """
    This method executes the HTTP requests and checks for the upgraded headers, if so, it handles the piped TCP stream
    """
    remote_socket = self._connect_to_remote()
    try:
      should_upgrade = self._execute_http_request(remote_socket, local_socket, request_line, headers, body)
      if should_upgrade:
        self._handle_tcp_upgrade(local_socket, remote_socket)
    finally:
      remote_socket.close()

  def _execute_http_request(self, remote_socket, local_socket, request_line, headers, body):
    """
    Execute the HTTP request and check if the connection has been upgraded
    """
    self._send_http_request(remote_socket, request_line, headers, body)
    return self._forward_http_response(remote_socket, local_socket)

  def _send_http_request(self, remote_socket, request_line, headers, body):
    """
    Sends the HTTP request to the remote socket
    """
    remote_socket.send(bytes('{0:s}\r\n'.format(request_line), self.encoding))
    for key in headers:
      header = headers.get(key)
      header = header if type(header) == list else [header]
      for item in header:
        remote_socket.send(bytes('{0:s}: {1:s}\r\n'.format(key, item), self.encoding))
    remote_socket.send(bytes('\r\n', self.encoding))
    if len(body) > 0:
      remote_socket.send(body)
    return remote_socket

  def _forward_http_response(self, remote_socket, local_socket):
    """
    Forwards the response from the remote socket to the local one
    """
    line_generator = self._read_http_lines(remote_socket)
    response_line = next(line_generator)[0]
    local_socket.send(response_line)

    headers = {}
    response_body = bytearray()
    while True:
      header_tuple = next(line_generator)
      header = header_tuple[0]
      local_socket.send(header)
      header_line = str(header, self.encoding).strip()
      if len(header_line) == 0:
        remaining = header_tuple[1]
        if remaining:
          response_body.extend(remaining)
        break
      header_parts = header_line.split(':')
      headers[header_parts[0].strip().lower()] = None if len(header_parts) <= 1 else header_parts[1].strip().lower()

    if 'content-length' in headers:
      content_length = int(headers['content-length'])
      if content_length > 0:
        if len(response_body) != content_length:
          response_body = next(line_generator)[0]
        local_socket.send(response_body)

    return 'content-type' in headers and headers['content-type'] == 'application/vnd.docker.raw-stream'

  def _handle_tcp_upgrade(self, local_socket, remote_socket):
    """
    Pipe the local and remote sockets:
    https://docs.docker.com/engine/api/v1.41/#tag/Container/operation/ContainerAttach
    """
    try:
      while True:
        frame = remote_socket.recv(8)
        if len(frame) == 0:
          break
        local_socket.send(frame)
        stream_type = int(frame[0])
        if stream_type == 0 or stream_type == 1 or stream_type == 2:
          size = int.from_bytes(frame[4:], byteorder='big', signed=False)
          data = remote_socket.recv(size)
          if len(data) != size:
            raise ValueError('Recieved {0:d} but was expecting {1:d}'.format(len(data), size))
          local_socket.send(data)
        else:
          raise ValueError('Unspported stream type {0:d}'.format(stream_type))

    except socket.error as serr:
      if serr.errno == errno.ECONNREFUSED:
        logging.debug('TCP upgrade failed - Connection refused')
      elif serr.errno == errno.ETIMEDOUT:
        logging.debug('TCP upgrade failed - Connection timeout')
      else:
        logging.exception('TCP upgrade failed')

  def _connect_to_remote(self):
    """
    Creates a socket to the remote endpoint taking into account UNIX domain sockets and secure sockets
    """
    if self.remote.scheme == 'unix':
      remote_socket = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
      remote_socket.settimeout(self.timeout)
      remote_socket.connect(requests.compat.unquote(self.remote.path))
    else:
      remote_socket = socket.create_connection((self.remote.hostname, self.remote.port), timeout=self.timeout)
      if self.args.secure:
        remote_socket = ssl.wrap_socket(remote_socket, keyfile=self.args.remote_key, certfile=self.args.remote_cert)

    return remote_socket

  def _read_http_lines(self, sock, end_line=bytes([13, 10])):
    """
    Returns a generator of http lines (separated by \r\n)
    """
    buffering = True
    remainder = None
    while buffering:
      try:
        buffer = sock.recv(self.buffer_size)
        exhausted = len(buffer) == 0
        if remainder:
          remainder.extend(buffer)
          buffer = remainder
          remainder = None

        if len(buffer) > 0:
          start = 0
          while True:
            end = buffer.find(end_line, start)
            if end < 0:
              if exhausted:
                yield (buffer[start:], None)
                buffering = False
              else:
                remainder = bytearray(buffer[start:])
              break
            end += 2
            yield (buffer[start:end], buffer[end:])
            start = end

      except socket.timeout:
        if remainder:
          yield (remainder, None)
        buffering = False

class ProxyHandler(BaseHTTPRequestHandler):
  """
  BaseHTTPRequestHandler that forwards Docker API requests to a remote endpoint taking into account hijacked
  connections done by the Docker API when starting and executing commands on containers

  An inspect request to the Docker API will be modified in order to create the required SSH forwards and a request to
  delete a container will remove such forwards
  """

  def __init__(self, proxy_server, *args, **kwargs):
    self.protocol_version = 'HTTP/1.1'
    self.proxy_server = proxy_server
    self.args = self.proxy_server.args
    self.remote = self.proxy_server.remote
    self.session = self.proxy_server.session
    self.request_handlers = [
      self._handle_upgrade_request,
      self._handle_default_request
    ]
    self.response_handlers = [
      self._handle_inspect_response,
      self._handle_delete_container_response,
      self._handle_default_response
    ]
    super().__init__(*args, **kwargs)

  def log_error(self, format, *args):
    logging.error(format, *args)

  def log_message(self, format, *args):
    if not args[1] == '400':
      logging.debug(format, *args)

  def do_GET(self):
    self._handle_request(self.session.get)

  def do_POST(self):
    self._handle_request(self.session.post)

  def do_PUT(self):
    self._handle_request(self.session.put)

  def do_DELETE(self):
    self._handle_request(self.session.delete)

  def do_HEAD(self):
    self._handle_request(self.session.head)

  def do_OPTIONS(self):
    self._handle_request(self.session.options)

  def _handle_request(self, method):
    """
    Handles a request to the Docker API, first it will try to discover if the request requires a TCP upgrade and finally
    it will hijack the response to create the required SSH forwards
    """
    url = self._resolve_remote_url()
    try:
      response = None
      request = self._parse_request_body()
      for handler in self.request_handlers:
        response = handler(url, request, method)
        if response:
          break

      if not hasattr(response, 'status_code'):
        return

      for handler in self.response_handlers:
        if handler(response):
          return

      raise RuntimeError('No response handler for {0:s}', url)

    except (ConnectionResetError, BrokenPipeError):
      logging.debug('Connection closed for %s', url)
    except:
      logging.exception('Failed to forward the request to %s', url)
      self.send_response_only(500, 'Failed to forward the request to {0:s}'.format(url))

  def forward_headers(self, response):
    """
    Forwards the response headers back to the client without any modification
    """
    self._send_headers(response.status_code, response.headers)

  def forward_response(self, response):
    """
    Forwards the response back to the client taking into account chunked responses
    """
    if response.headers.get('transfer-encoding', '') == 'chunked':
      self._send_chunked_response(response)
    else:
      self._send_full_response(response.content)

  def _send_headers(self, status_code, headers):
    self.send_response_only(status_code)
    for key in headers:
      self.send_header(key, headers[key])
    self.end_headers()

  def _send_chunked_response(self, response):
    encoding = response.encoding
    if not encoding:
      encoding = 'utf-8'
    for chunk in response.iter_content(chunk_size=None, decode_unicode=False):
      self.wfile.write(bytes('{0:x}\r\n'.format(len(chunk)), encoding))
      self.wfile.write(chunk)
      self.wfile.write(bytes('\r\n', encoding))
    self.wfile.write(bytes('0\r\n\r\n', encoding))

  def _send_full_response(self, response):
    if self.command != 'HEAD':
      self.wfile.write(response)

  def _parse_request_body(self):
    content_length = int(self.headers.get('content-length', '0'))
    return self.rfile.read(content_length)

  def _resolve_remote_url(self):
    url = self.proxy_server.remote_url
    request_parts = urllib.parse.urlparse(self.path)
    if request_parts.path != '/':
      url += request_parts.path
    if request_parts.query != '':
      url += '?' + request_parts.query
    return url

  def _handle_default_request(self, url, body, requests_method):
    """
    Handles a normal request to the Docker API
    """
    response = requests_method(url, data=body, headers=self.headers, stream=True)
    logging.debug('FORWARD "%s %s" [status: %s] -> "%s"', self.command, self.path, response.status_code, url)
    return response

  def _handle_upgrade_request(self, url, body, request_method):
    """
    Handles an upgradeable request to the Docker API
    """
    if self._is_exec_start_command(self.command, self.path, body) or self._is_attach_command(self.command, self.path):
      try:
        logging.debug('FORWARD "%s %s" [status: 101?] -> "%s"', self.command, self.path, url)
        tcp_upgrade = TcpUpgrade(self.proxy_server)
        tcp_upgrade.try_tcp_upgrade(self.connection, self.requestline, self.headers, body)
      finally:
        self.close_connection = True
      return True

    return None

  def _handle_inspect_response(self, response):
    """
    Handles an inspect container request to the Docker API and creates the required SSH forwards
    """
    if response.status_code != 200:
      return False

    container_id = self._is_inspect_command(self.command, self.path)
    if not container_id:
      return False

    info_json = self.proxy_server.on_container(container_id, response.content)
    info = json.dumps(info_json).encode('utf-8')

    headers = {}
    for header in response.headers:
      if not header.lower() in ['transfer-encoding', 'content-encoding', 'content-length']:
        headers[header] = response.headers[header]
    headers['Content-Length'] = len(info)

    self._send_headers(response.status_code, headers)
    self._send_full_response(info)
    return True

  def _handle_delete_container_response(self, response):
    """
    Handles a delete container request to the Docker API and removes the created SSH forwards
    """
    if response.status_code != 204:
      return False

    container_id = self._is_delete_command(self.command, self.path)
    if not container_id:
      return False

    self.proxy_server.delete_container_forwards(container_id)
    self.forward_response(response)
    self.forward_headers(response)
    return True

  def _handle_default_response(self, response):
    """
    Forwards the response back to the client without any modification
    """
    self.forward_headers(response)
    self.forward_response(response)
    return True

  @staticmethod
  def _is_exec_start_command(method, path, body):
    if method != 'POST':
      return False

    matcher = re.search('^/v.+/exec/(?P<container_id>[^?/]+)/start.*$', path.strip())
    if not matcher:
      return None

    container_id = matcher.groupdict().get('container_id')
    body_json = json.loads(body)
    if not body_json['Detach']:
      if body_json['Tty']:
        raise RuntimeError('Handling TTY sessions not supported by forwarder')

      return container_id

    return None

  @staticmethod
  def _is_attach_command(method, path):
    if method != 'POST':
      return False

    matcher = re.search('/v.+/containers/(?P<container_id>[^?/]+)/attach.*$', path.strip())
    if matcher:
      return matcher.groupdict().get('container_id')
    return None

  @staticmethod
  def _is_inspect_command(method, path):
    if method != 'GET':
      return False

    matcher = re.search('^/v.+/containers/(?P<container_id>[^?/]+)/json.*$', path.strip())
    if matcher:
      return matcher.groupdict().get('container_id')
    return None

  @staticmethod
  def _is_delete_command(method, path):
    if method != 'DELETE':
      return False

    matcher = re.search('^/v.+/containers/(?P<container_id>[^?/]+).*$', path.strip())
    if matcher:
      return matcher.groupdict().get('container_id')
    return None


class PortForwarder:
  """
  Class in charge of creating persistent SSH forwards via autossh, first it will try to reserve an available port
  and then it will execute the forward if the remote is listening.
  """

  def __init__(self, remote_port, remote_address=""):
    self.remote_port = remote_port
    self.remote_address = remote_address
    self.socket = self._init_local_port(self.remote_port)
    self.local_port = self.socket.getsockname()[1]
    self.process = None

  def forward(self):
    """
    Tries to forward via SSH the port that is booked by the local socket
    """
    if self._is_forwarding():
      return

    if self._is_remote_port_listening():
      if self.socket:
        self.socket.close()
        self.socket = None
      try:
        self.process = self._forward_port()
        logging.info('SSH_FORWARD %s -> %s established', self.remote_port, self.local_port)
      except Exception as e:
        logging.debug('FAILED_FORWARD %s -> %s, reason: %s', self.remote_port, self.local_port, e)
        self.socket = self._bind_to_port(self.local_port)

  def close(self):
    if self.process:
      self.process.kill()
      self.process = None
    if self.socket:
      self.socket.close()
      self.socket = None

  def _is_forwarding(self):
    """
    Checks if the ssh forward process has not exited
    """
    return self.process and not self.process.poll()

  def _forward_port(self):
    process = subprocess.Popen(
      [
        'autossh',
        '-M', '0',
        '-gNC',
        '-o', 'ExitOnForwardFailure=yes',
        '-o', 'ServerAliveInterval=10',
        '-o', 'ServerAliveCountMax=3',
        '-L', '127.0.0.1:{0:d}:localhost:{1:d}'.format(self.local_port, self.remote_port),
        self.remote_address,
      ], stdout=subprocess.PIPE, stderr=subprocess.PIPE
    )
    time.sleep(0.25)
    poll = process.poll()
    if poll:
      communicate = process.communicate()
      stdout = str(communicate[0], sys.getdefaultencoding())
      stderr = str(communicate[1], sys.getdefaultencoding())
      raise RuntimeError(
        'SSH forward exited with code {0:d}\noutput:\n{1:s}\nerr:\n{2:s}'.format(poll, stdout, stderr))
    return process

  def _is_remote_port_listening(self):
    """
    Checks via SSH if the remote port is already listening
    """
    try:
      lsof = subprocess.Popen(
        [
          'ssh',
          self.remote_address,
          '/bin/sh -c "sudo lsof -i:{0:d} | grep \'LISTEN\'"'.format(self.remote_port)
        ], stdout=subprocess.PIPE, stderr=subprocess.PIPE
      )
      lsof.wait()
      communicate = lsof.communicate()
      std_output = str(communicate[0], sys.getdefaultencoding())
      if lsof.returncode != 0:
        std_error = str(communicate[1], sys.getdefaultencoding())
        raise RuntimeError(
          'Check remote port failed with exit code {0:d}\noutput:\n{1:s}\nerror:\n{2:s}'.format(lsof.returncode,
                                                                                                std_output,
                                                                                                std_error))
      return 'LISTEN' in std_output
    except Exception as e:
      logging.debug('Check remote port failed, reason: %s', e)
      return False

  def _init_local_port(self, port):
    """
    Finds an open port in the current server and binds to it without listening, effectively blocking the port
    """
    for current_port in [port, 0]:
      try:
        return self._bind_to_port(current_port)
      except Exception as e:
        logging.debug('Port %s is in use, reason: %s', current_port, e)

    raise RuntimeError('Cannot find an open port in the system')

  @staticmethod
  def _bind_to_port(port):
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.bind(("localhost", port))
    return sock


class DockerProxy:
  """
  Proxy server for the Docker API with dictionary containing the open SSH forwards for the different containers.
  """

  def __init__(self, args):
    self.args = args
    self.remote = urllib.parse.urlparse(self.args.remote)
    if self.remote.scheme == 'unix':
      self.session = requests_unixsocket.Session('http+unix://')
      self.remote_url = 'http+unix://{0:s}'.format(requests.compat.quote_plus(self.remote.path))
    else:
      self.session = requests.Session()
      if args.secure:
        self.remote_url = self.args.remote.replace('tcp://', 'https://')
        self.session.cert = (self.args.remote_cert, self.args.remote_key)
        self.session.verify = self.args.remote_ca
      else:
        self.remote_url = self.args.remote.replace('tcp://', 'http://')
    self.forwards = {}
    self.running = threading.Event()
    self.running.set()
    self.server = ThreadingHTTPServer(('localhost', self.args.local_port), partial(ProxyHandler, self))

  def start(self):
    self.running.set()
    forward_thread = threading.Thread(target=self.run_port_forwarding)
    forward_thread.start()

    server_thread = threading.Thread(target=self.run_http_server)
    server_thread.start()

    logging.info('Server started successfully [%s]: --port: %s --remote: %s%s',
                 os.getpid(), self.args.local_port, self.args.remote, ' --secure' if self.args.secure else '')

    forward_thread.join()
    server_thread.join()

  def stop(self):
    logging.info('Stopping docker forwarder')
    self.running.clear()
    self.delete_all_forwards()
    self.server.shutdown()

  def on_container(self, container_id, container_json):
    """
    Method called when an inspect container request is received, this method should navigate through the mapped ports
    and provide the required SSH forwards
    """
    container_info = json.loads(container_json)
    image = container_info['Config']['Image']
    ports = container_info['NetworkSettings']['Ports']
    for container_port in ports:
      port_mappings = ports[container_port]
      if port_mappings:
        for mapping in port_mappings:
          host_port = int(mapping['HostPort'])
          forwarded_port = self.get_or_create_forward(container_id, image, host_port)
          mapping['HostPort'] = str(forwarded_port)
    return container_info

  def get_or_create_forward(self, container_id, image, host_port):
    """
    Try to find a forward for the select container and port, if none is found a new ssh_forward will be created using
    the same port as preferred
    """
    if container_id in self.forwards:
      container_forwards = self.forwards[container_id]
    else:
      container_forwards = {}
      self.forwards[container_id] = container_forwards

    if host_port in container_forwards:
      forwarder = container_forwards[host_port]
      return forwarder.local_port
    else:
      try:
        forwarder = PortForwarder(host_port, self.args.forward)
        logging.info('ADD_FORWARD %s -> %s for container %s - %s', host_port, forwarder.local_port, image, container_id)
        container_forwards[host_port] = forwarder
        return forwarder.local_port
      except:
        logging.exception('FAIL_FORWARD %s for container %s - %s', host_port, image, container_id)
        return host_port

  def delete_container_forwards(self, container_id):
    """
    Deletes all the forwards for the chosen container
    Deletes all the forwards for the chosen container
    """
    if container_id in self.forwards:
      container_forwards = self.forwards.pop(container_id)
      for host_port in container_forwards:
        forwarder = container_forwards[host_port]
        try:
          forwarder.close()
          logging.info('DEL_FORWARD %s for container %s', host_port, container_id)
        except Exception as e:
          logging.debug('Failed to stop forward for port %s and container %s, reason: %s', host_port, container_id, e)

  def delete_all_forwards(self):
    for container_id in list(self.forwards):
      self.delete_container_forwards(container_id)

  def run_http_server(self):
    self.server.serve_forever()

  def run_port_forwarding(self):
    while self.running.is_set():
      try:
        for container_id in list(self.forwards):
          container_forwards = self.forwards[container_id]
          for port in container_forwards:
            forward = container_forwards[port]
            try:
              forward.forward()
            except Exception as e:
              logging.debug('Failed port forwarding for container %s, reason: %s', container_id, e)
        time.sleep(1)
      except:
        logging.exception('Server failed to forward ports')


def is_file(path):
  if not path:
    return False
  return Path(path).is_file()


def main():
  args = parse_args()

  logging.basicConfig(format='%(asctime)s %(levelname)-8s %(message)s',
                      level=logging.DEBUG if args.verbose else logging.INFO,
                      datefmt='%Y-%m-%d %H:%M:%S')
  logging.getLogger('urllib3.connectionpool').setLevel(logging.ERROR)

  if not args.remote:
    logging.error('Remote is required (e.g. tcp://0.0.0.0:2375, unix:///var/run/docker.sock): --remote')
    sys.exit(1)
  else:
    remote_parts = urllib.parse.urlparse(args.remote)
    scheme = remote_parts.scheme
    if not scheme in ['unix', 'tcp']:
      logging.error('Remote not valid, only "unix" and "tcp" schemes supported, received "{0:s}"'.format(scheme))
      sys.exit(1)

  if args.secure:
    if not is_file(args.server_cert) or not is_file(args.server_key):
      logging.error('Server certificates are required when secure is enabled: --server-cert --server-key')
      sys.exit(1)

    if not is_file(args.remote_cert) or not is_file(args.remote_key) or not is_file(args.remote_ca):
      logging.error('Remote certificates are required when secure is enabled: --remote-ca --remote-cert --remote-key')
      sys.exit(1)

  proxy = DockerProxy(args)

  def handler(*_):
    proxy.stop()

  signal.signal(signal.SIGINT, handler)

  if args.secure:
    proxy.server.socket = ssl.wrap_socket(proxy.server.socket, keyfile=args.server_key, certfile=args.server_cert,
                                          server_side=True)
  proxy.start()


if __name__ == '__main__':
  main()
