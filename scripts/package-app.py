from pathlib import Path
from zipfile import ZipFile, ZIP_STORED
import shutil

shutil.copyfile('build/app/resources.apk', 'build/app/unsigned.apk')
with ZipFile('build/app/unsigned.apk', 'a') as apk:
    for dex in sorted(Path('build/app/dex').glob('classes*.dex')):
        apk.write(dex, dex.name, compress_type=ZIP_STORED)
