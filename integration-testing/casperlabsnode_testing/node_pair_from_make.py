from typing import Optional
import docker
import subprocess


class NodePair:
    def __init__(self, node_number: int, client: docker.DockerClient, make_path: str) -> None:
        self.node_number = node_number
        self.client = client
        self.make_path = make_path
        self.node_container = None
        self.ee_container = None
        self.private_key = None
        self.public_key = None
        self._make_node_up()
        self._get_container_refs()

    def __repr__(self) -> str:
        return f'<NodePair #{self.node_number}>'

    def shutdown(self):
        self._make_node_down()

    def _make_node_up(self):
        # TODO - I don't think we can attach for logging of image startup in any way.  Do we need to?
        output = subprocess.check_output(f'cd {self.make_path} && make node-{self.node_number}/up', shell=True)
        self._set_keys_from_output(output.decode('UTF_8'))

    def _make_node_down(self):
        cp = subprocess.run(f'cd {self.make_path} && make node-{self.node_number}/down', shell=True)
        # if cp.returncode != 0:
        #     print(cp.stderr)

    def _set_keys_from_output(self, output: str):
        PUBLIC_START = 'CL_VALIDATOR_PUBLIC_KEY='
        PRIVATE_START = 'CL_VALIDATOR_PRIVATE_KEY='
        END_TEXT = ' >> node-'
        if PUBLIC_START in output:
            self.public_key = output.split(PUBLIC_START)[1].split(END_TEXT)[0]
        if PRIVATE_START in output:
            self.private_key = output.split(PRIVATE_START)[1].split(END_TEXT)[0]

    def _get_container_by_name(self, node_name: str) -> Optional['Container']:
        container_list = [cont for cont in self.client.containers.list() if cont.name == node_name]
        if not container_list:
            return None
        return container_list[0]

    def _get_container_refs(self):
        self.node_container = self._get_container_by_name(f'node-{self.node_number}')
        if not self.node_container:
            raise Exception(f"Docker container 'node-{self.node_number}' not found.")

        self.ee_container = self._get_container_by_name(f'execution-engine-{self.node_number}')
        if not self.ee_container:
            raise Exception(f"Docker container 'execution-engine-{self.node_number}' not found.")


if __name__ == "__main__":
    # TODO Pass in path from run_tests.sh?  Not sure the best way of tracking this.
    make_path = '../../docker'
    client = docker.from_env()
    np = NodePair(0, client, make_path)
    # np2 = NodePair(1, client, make_path)
    print(np.node_container)
    print(np.ee_container)
    print(np.private_key)
    print(np.public_key)
    # np2.shutdown()
    np.shutdown()

