from io import TextIOWrapper, BytesIO
from urllib.request import urlopen
from zipfile import ZipFile
import json
import os
from os import path

def mkdirs(dir):
    dir = path.abspath(dir)
    if not path.exists(dir):
        os.makedirs(dir)

def open_zip_url(url):
    with urlopen(url) as response:
        return ZipFile(BytesIO(response.read()))


def open_text_url(url):
    return TextIOWrapper(urlopen(url))


def load_json_url(url):
    with open_text_url(url) as response:
        return json.load(response)