"""
Get the spigot 1.8 build tool mappings

Requires SrgLib (from pypi)
"""
import json
from io import TextIOWrapper
from urllib.request import urlopen

import srg
import srg.parser
from srg.mappings import ChainedMappings, PackageMappings


def get_builddata_commit(rev):
    """
    Get the build data commit from the specified spigot reveision

    The Spigot revision is specified using '--rev=1.8' as a buildtools option
    Available revisions: https://hub.spigotmc.org/versions/

    :param rev: the build data revision
    :return: the build data commit-id for this revision
    """
    url = 'https://hub.spigotmc.org/versions/' + rev + '.json'
    return _load_json_url(url)["refs"]["BuildData"]


def download_mappings(ref):
    baseUrl = 'https://hub.spigotmc.org/stash/projects/SPIGOT/repos/builddata/browse/'
    info = _load_json_url(baseUrl + 'info.json?at=' + ref + '&raw')
    with _open_text_url(baseUrl + 'mappings/' + info["classMappings"] + "?at=" + ref + "&raw") as srg_file:
        lines = srg_file.read().splitlines(False)
        clear_broken(lines)
        classes = srg.parser.parse_compact_lines(lines)
    with _open_text_url(baseUrl + 'mappings/' + info["memberMappings"] + "?at=" + ref + "&raw") as srg_file:
        lines = srg_file.read().splitlines(False)
        clear_broken(lines)
        members = srg.parser.parse_compact_lines(lines)
    packages = dict()
    with _open_text_url(baseUrl + 'mappings/' + info["packageMappings"] + "?at=" + ref + "&raw") as package_file:
        for line in package_file:
            line = line.strip()
            if line.startswith("#") or len(line) == 0:
                continue
            split = line.split(" ")
            original = split[0]
            renamed = split[1]
            if not original.endswith('/') or not renamed.endswith('/'):
                raise ValueError("Not a package: " + line)
            # Strip trailing '/'
            original = original[:-1]
            renamed = renamed[:-1]
            # Strip leading '.' if present
            if original.startswith('.'):
                original = original[1:]
            if renamed.startswith('.'):
                renamed = renamed[1:]
            original = original.replace('/', '.')
            renamed = renamed.replace('/', '.')
            if not srg.is_valid_package(original):
                raise ValueError("Invalid package: " + original)
            if not srg.is_valid_package(renamed):
                raise ValueError("Invalid package: " + renamed)
            packages[original] = renamed
    return ChainedMappings((classes, members, PackageMappings(packages)))


def _open_text_url(url):
    return TextIOWrapper(urlopen(url))


def _load_json_url(url):
    with _open_text_url(url) as response:
        return json.load(response)

# Mistake fixer
broken_lines = [
    "IDispenseBehavior a(LISourceBlock;LItemStack;)LItemStack; dispense" # md_5 1.8.8 update mistake
]


def clear_broken(lines):
    for broken_line in broken_lines:
        if broken_line in lines:
            lines.remove(broken_line)