"""Ship the canonical schema files in wheels without maintaining a second source copy."""
from pathlib import Path
from shutil import copy2
from setuptools import setup
from setuptools.command.build_py import build_py


class BuildWithSchema(build_py):
    def run(self):
        super().run()
        destination = Path(self.build_lib) / "aeroml" / "_schemas"
        destination.mkdir(parents=True, exist_ok=True)
        schemas = list((Path(__file__).parent / "schema").glob("feature_schema_v*.json"))
        if not schemas:
            raise RuntimeError("canonical feature schemas are missing from the source distribution")
        for schema in schemas:
            copy2(schema, destination / schema.name)


setup(cmdclass={"build_py": BuildWithSchema})
