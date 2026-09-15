
from __future__ import annotations
import sys
from io import StringIO
# from argparse import ArgumentError
from lib.ff.cmdline import DebugArgumentParser
import lib
from lib.dev.main import base_args_parser, predefine as tui_predefine

# restore sys.argv for command line parsing in DEBUG & TESTS, see __init__.py
# sys.argv = lib.cmdline_argv

actions = ['ff', 'web', 'userjs', 'info']

base = base_args_parser()
parser = DebugArgumentParser(parents=[base], add_help=False)
parser.add_argument('action', choices=actions, default=None, help='action to run')
old_stderr = sys.stderr
sys.stderr = errors = StringIO()  # suppress argparse error message
try:
    args, argv = parser.parse_known_args()
except SystemExit:
    if '--help' in lib.cmdline_argv:
        parser.print_help()
    else:
        print(errors.getvalue(), end='', file=old_stderr)  # print argparse error message if any
    raise
finally:
    sys.stderr = old_stderr

print(args)
lib.cmdline_argv = [*lib.cmdline_argv[:1], *argv]  # update cmdline_argv for the rest of the code

if args.action == 'ff':
    del lib.cmdline_argv[1]
    from lib.dev.main import parse_args as tui_parse_args, main as tui  # , ServiceMode
    print(f'{sys.argv=}')
    cli_args, cli_argv = tui_predefine(args=tui_parse_args(args=args))
    from lib import main
    tui(args=cli_args)
elif args.action == 'web':
    from lib.service.web_server import cli_define, main
    main(args=cli_define())
elif args.action == 'userjs':
    from lib.service.web_server import main
    p = base_args_parser()
    p.add_argument('user_js', choices=['stable', 'beta'], help='cat fanfilm.user.js file (for stable or beta version)')
    p.add_argument('--help', action='help', help='show this help message and exit')
    main(args=p.parse_args())
elif args.action == 'info':
    parser = base_args_parser()
    args, argv = parser.parse_known_args()
    args, argv = tui_predefine(args=args)
    from lib.ff.info import main
    main(parser=parser)
