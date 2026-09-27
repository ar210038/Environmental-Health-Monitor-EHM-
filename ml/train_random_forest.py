"""Compatibility entry point for the verified EHM training pipeline.

New commands should invoke ``train_ehm.py`` directly. Keeping this small entry
point avoids leaving the previous, incompatible feature pipeline in the tree.
"""

from train_ehm import main


if __name__ == "__main__":
    main()
