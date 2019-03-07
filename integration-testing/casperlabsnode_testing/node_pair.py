import docker
import tarfile
from io import BytesIO
import time


def create_archive(artifact_file):
    pw_tarstream = BytesIO()
    pw_tar = tarfile.TarFile(fileobj=pw_tarstream, mode='w')
    file_data = open(artifact_file, 'r').read()
    tarinfo = tarfile.TarInfo(name=artifact_file)
    tarinfo.size = len(file_data)
    tarinfo.mtime = time.time()
    # tarinfo.mode = 0600
    pw_tar.addfile(tarinfo, BytesIO(file_data.encode('UTF-8')))
    pw_tar.close()
    pw_tarstream.seek(0)
    return pw_tarstream


class NodePair:
    """
    Holds both a node and execution-engine docker container for one network node.
    """
    def __init__(self, client, node_number: int) -> None:
        self.client = client
        self.network = self._get_network()
        self.socket_volume = self._create_socket_volume()
        self.node_number = node_number
        self.ee_container = None  # self._run_ee_container('test')
        self.node_container = self._run_node_container('test')

    def __repr__(self) -> str:
        return f'<NodePair(node-{self.node_number})>'

    def _get_network(self):
        cl_network = [net for net in self.client.networks.list() if net.name == 'casperlabs']
        if cl_network:
            return cl_network[0]
        return self.client.networks.create(name='casperlabs', internal=False)

    def _create_socket_volume(self):
        # TODO - Will this create a local one to these two containters or do we need to name it special?
        return self.client.volumes.create(name='socketvolume', driver='local')

    def shutdown(self):
        if self.node_container:
            self.node_container.stop()
            self.node_container.remove()
        if self.ee_container:
            self.ee_container.stop()
            self.ee_container.remove()

    def _run_node_container(self, version: str):
        """
          node:
    image: casperlabs/node:${CL_VERSION}
    container_name: node-${NODE_NUMBER}
    hostname: node-${NODE_NUMBER}
    volumes:
      # Volume for a socket to be created and shared with the execution engine.
      - socketvolume:/root/.casperlabs/sockets
      # Common bonds files. Don't map the .casperlabs directory itself so nodes can create SSL keys.
      # Using files created by running the node once as described in https://slack-files.com/TDVFB45LG-FFBGDQSBW-bad20239ec
      # Later all need to agree on the same validators so these are committed to git and mounted.
      - $PWD/../.casperlabs/genesis:/root/.casperlabs/genesis
      - $PWD/../.casperlabs/bootstrap:/root/.casperlabs/bootstrap
      - $PWD/../template/start-node.sh:/opt/docker/start-node.sh
    networks:
      - casperlabs
    environment:
      HOME: /root
      # Got the ID from the logs by running the node once. They keys are going to be the same for node-0.
      # i.e. "Listening for traffic on casperlabs://..."
      BOOTSTRAP_HOSTNAME: node-0
      CL_VALIDATOR_PUBLIC_KEY: $CL_VALIDATOR_PUBLIC_KEY
      CL_VALIDATOR_PRIVATE_KEY: $CL_VALIDATOR_PRIVATE_KEY
      CL_GRPC_SOCKET: /root/.casperlabs/sockets/.casper-node.sock
    entrypoint:
      - sh
      - -c
      - chmod +x ./start-node.sh && ./start-node.sh
    depends_on:
      # Need the gRPC socket file to be created before we start the node.
      - execution-engine
      """

# .env
# NODE_NUMBER=0
# CL_VERSION=latest
# CL_VALIDATOR_PUBLIC_KEY=06f398f172b1b96cf7f410382795b0a86bc1b241a3063d82a379ccee826809dd
# CL_VALIDATOR_PRIVATE_KEY=22b3a527698094fef7e3082954a158d8033fbce20993bd05baba22e63f7b3e62

# printenv
# LANG=C.UTF-8
# HOSTNAME=node-0
# JAVA_HOME=/docker-java-home
# JAVA_VERSION=11.0.2
# PWD=/opt/docker
# HOME=/root
# BOOTSTRAP_HOSTNAME=node-0
# CL_GRPC_SOCKET=/root/.casperlabs/sockets/.casper-node.sock
# JAVA_DEBIAN_VERSION=11.0.2+9-3~bpo9+1
# CL_VALIDATOR_PRIVATE_KEY=22b3a527698094fef7e3082954a158d8033fbce20993bd05baba22e63f7b3e62
# TERM=xterm
# CL_VALIDATOR_PUBLIC_KEY = 06f398f172b1b96cf7f410382795b0a86bc1b241a3063d82a379ccee826809dd
# SHLVL=1
# PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
# _=/usr/bin/printenv

        try:
            container = self.client.containers.create(
                image=f'casperlabs/node:{version}',
                user='root',
                detach=True,
                name=f'node-{self.node_number}',
                hostname=f'node-{self.node_number}',
                network=self.network.name,
                volumes=[
                    'socketvolume:/root/.casperlabs/socket',
                    # 'template/start-node.sh:/opt/docker/start-node.sh',
                ],
                environment={
                    'HOME': '/root',
                    'NODE_NUMBER': self.node_number,
                    'CL_VERSION': {version},
                    'CL_VALIDATOR_PUBLIC_KEY': '00322ba649cebf90d8bd0eeb0658ea7957bcc59ecee0676c86f4fec517c06251',
                    'CL_VALIDATOR_PRIVATE_KEY': '901b1f0837b7e891d7c2ea0047f502fd95637e450b0226c39a97d68dd951c8a7',
                    'BOOTSTRAP_HOSTNAME': "node-0",
                    'CL_GRPC_SOCKET': "/root/.casperlabs/sockets/.casper-node.sock"
                },
                entrypoint=['sh',
                            '-c',
                            'chmod +x ./start-node.sh && ./start-node.sh'],
            )

            with create_archive('docker/start-node.sh') as archive:
                container.put_archive(path='/opt', data=archive)

            container.start()
            # container.exec_run('/opt/docker/start-node.sh')
            return container
        except Exception as e:
            print(e)
            return None

    def _run_ee_container(self, version: str):
        """
            image: casperlabs/execution-engine:${CL_VERSION}
            container_name: execution-engine-${NODE_NUMBER}
            hostname: execution-engine-${NODE_NUMBER}
            volumes:
              - socketvolume:/opt/docker/.casperlabs/sockets
            networks:
              - casperlabs
            command:
              - .casperlabs/sockets/.casper-node.sock
        """
        container = self.client.containers.run(
            image=f'casperlabs/execution-engine:{version}',
            user='root',
            detach=True,
            name=f'execution-engine-{self.node_number}',
            hostname=f'execution-enginer-{self.node_number}',
            network=self.network.name,
            volumes=[
                'socketvolume:/opt/docker/.casperlabs/socket',
            ],
            command='/opt/docker/.casperlabs/socket/.casper-node.sock',
            # entrypoint=['touch /opt/docker/.casperlabs/socket/.casper-node.sock',
            #             'ls -alR',
            #             '/opt/docker/.casperlabs/socket]
        )
        return container

    def ee_logs(self):
        if self.ee_container:
            return self.ee_container.logs().decode('UTF-8')
        return None

    def node_logs(self):
        if self.node_container:
            return self.node_container.logs().decode('UTF-8')
        return None


if __name__ == '__main__':
    client = docker.from_env()
    print([(net.name, net.id) for net in client.networks.list()])
    np = NodePair(client, 0)
    # print(np)
    # print(np.network)
    print([(net.name, net.id) for net in client.networks.list()])
    print(np.node_logs())
    print(np.ee_logs())
    input('hold till I say stop')
    np.shutdown()

