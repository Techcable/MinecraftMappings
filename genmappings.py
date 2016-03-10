#!/usr/bin/env python3
from os import path
from argparse import ArgumentParser
from typing import Dict

from srg import FieldData
from srg import MethodData
from srg import Type

from utils import *
from csv import DictReader
import srg.parser
from srg.output import serialize_srg
from srg.mappings import RenamingMappings, ChainedMappings, AbstractMappings, ImmutableMappings
import spigot

def strip_duplicates(mappings: AbstractMappings) -> ImmutableMappings:
    classes = dict()  # type: Dict[Type, Type]
    fields = dict()  # type: Dict[FieldData, FieldData]
    methods = dict()  # type: Dict[MethodData, MethodData]
    for original, renamed in mappings.classes():
        if original == renamed: continue
        classes[original] = renamed
    for original, renamed in mappings.fields():
        if original.name == renamed.name: continue
        fields[original] = renamed
    for original, renamed in mappings.methods():
        if original.name == renamed.name: continue
        methods[original] = renamed
    return ImmutableMappings(classes, fields, methods)


def download_mcp_mappings(srg_mappings, version, mappings_version):
    mappings_channel = mappings_version[:mappings_version.rindex('_')]
    mappings_id = mappings_version[mappings_version.rindex('_') + 1:]
    field_names = dict()
    method_names = dict()
    with open_zip_url('http://export.mcpbot.bspk.rs/mcp_{1}/{2}-{0}/mcp_{1}-{2}-{0}.zip'.format(version, mappings_channel, mappings_id)) as zip:
        with TextIOWrapper(zip.open('fields.csv', 'rU')) as fields_file:
            for row in DictReader(fields_file):
                original = row['searge']
                renamed = row['name']
                field_names[original] = renamed
        with TextIOWrapper(zip.open('methods.csv', 'rU')) as methods_file:
            for row in DictReader(methods_file):
                original = row['searge']
                renamed = row['name']
                method_names[original] = renamed

    return RenamingMappings(None, lambda original: method_names[original.name] if original.name in method_names else original.name, lambda original: field_names[original.name] if original.name in field_names else original.name).transform_mappings(srg_mappings)


def download_srg_mappings(version):
    with open_zip_url('http://files.minecraftforge.net/maven/de/oceanlabs/mcp/mcp/{0}/mcp-{0}-srg.zip'.format(version)) as zip:
        with zip.open('joined.srg', 'rU') as joined_srg:
            return srg.parser.parse_lines(joined_srg.read().decode('utf-8').splitlines(False))

# Command Line Interface


parser = ArgumentParser(description="Generate as as many mappings as possible for the minecraft version")
parser.add_argument('version', help='The version to generate')
parser.add_argument('mappings_version', help='The mcpbot mappings version')

AVAILABLE_VERSIONS = ('1.8', '1.8.8', '1.9')

# We have to use 'parse_known_args', or the intellij debuger explodes
args = parser.parse_known_args()[0]
if args.version not in AVAILABLE_VERSIONS:
    print("Unknown version:", args.version)
    exit(1)

out_folder = args.version
mkdirs(out_folder)


def write_mapping(mappings: AbstractMappings, file_name: str):
    mappings = strip_duplicates(mappings)
    file_name = path.join(out_folder, file_name)
    with open(file_name, "wt+") as file:
        lines = list(serialize_srg(mappings))
        lines.sort()
        for line in lines:
            file.write(line)
            file.write('\n')

srg_mappings = download_srg_mappings(args.version)
mcp_mappings = download_mcp_mappings(srg_mappings, args.version, args.mappings_version)
build_data_commit = spigot.get_builddata_commit(args.version)
spigot_mappings = spigot.download_mappings(build_data_commit)

# MCP mappings

write_mapping(srg_mappings, 'obf2srg.srg')
write_mapping(srg_mappings.invert(), 'srg2obf.srg')
write_mapping(mcp_mappings, 'srg2mcp.srg')
write_mapping(mcp_mappings.invert(), 'mcp2srg.srg')

obf2mcp = ChainedMappings((srg_mappings, mcp_mappings))
write_mapping(obf2mcp, 'obf2mcp.srg')
write_mapping(obf2mcp.invert(), 'mcp2obf.srg')

# Spigot mappings

write_mapping(spigot_mappings, 'obf2spigot.srg')
write_mapping(spigot_mappings.invert(), 'spigot2obf.srg')

# Spigot <-> MCP
spigot2srg = ChainedMappings((spigot_mappings.invert(), srg_mappings))
spigot2mcp = ChainedMappings((spigot2srg, mcp_mappings))
write_mapping(spigot2srg, 'spigot2srg.srg')
write_mapping(spigot2srg.invert(), 'srg2spigot.srg')
write_mapping(spigot2mcp, 'spigot2mcp.srg')
write_mapping(spigot2mcp.invert(), 'mcp2spigot.srg')
