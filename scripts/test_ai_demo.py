import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("ai_demo_starter", Path(__file__).with_name("start-ai-demo.py"))
starter = importlib.util.module_from_spec(spec)
spec.loader.exec_module(starter)


class LocalConfigurationTest(unittest.TestCase):
    def test_keys_are_data_not_shell_and_existing_values_are_preserved(self):
        with tempfile.TemporaryDirectory() as directory:
            config = Path(directory) / ".env"
            command = f"$(touch {Path(directory) / 'should-not-exist'})"
            config.write_text(f"ARK_API_KEY='{command}'\nVOLCENGINE_API_KEY=from-file\nUNRELATED=ignored\n")
            env = {"VOLCENGINE_API_KEY": "existing"}
            starter.load_local_keys(config, env)
            self.assertEqual(env, {"ARK_API_KEY": command, "VOLCENGINE_API_KEY": "existing"})
            self.assertFalse((Path(directory) / "should-not-exist").exists())

    def test_invalid_value_is_rejected_without_echoing_credential(self):
        with tempfile.TemporaryDirectory() as directory:
            config = Path(directory) / ".env"
            config.write_text("ARK_API_KEY=secret extra-word\n")
            with self.assertRaises(ValueError) as error:
                starter.load_local_keys(config, {})
            self.assertNotIn("secret", str(error.exception))


if __name__ == "__main__":
    unittest.main()
