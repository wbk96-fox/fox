"""Common module for all kind of lists."""

from __future__ import annotations
from typing import Sequence, TYPE_CHECKING
from typing_extensions import TypedDict, NotRequired, Literal, TypeAlias
import re
from itertools import chain
from pathlib import Path
from time import monotonic
from attrs import frozen, define, field
from .item import FFItem
from ..defs import FFRef, RefType
from ..ff import control
from ..ff.log_utils import fflog, fflog_exc
from ..kolang import L
from cdefs import ListType, ListTarget, ListPointer, ShowCxtMenu
from const import const
if TYPE_CHECKING:
    from typing import Iterator, Iterable, ClassVar
    from ..defs import AllIdsDict, MediaRef
    from xbmcgui import ListItem
    # from ..api.tmdb import UserGeneralListType


# class AddToServices(TypedDict, extra_items=Sequence[ListPointer]):
#     """All services and theirs list settings. Keys are ListService (defined pointers) or any (any pointers)."""
#     local: NotRequired[Sequence[Literal['library', 'own:favorites', 'own:watchlist', 'own:user', 'logs']]]
#     own: NotRequired[Sequence[Literal['own:favorites', 'own:watchlist', 'own:user']]]
#     trakt: NotRequired[Sequence[Literal['trakt:favorites', 'trakt:watchlist', 'trakt:collection', 'trakt:user']]]
#     tmdb: NotRequired[Sequence[Literal['tmdb:favorites', 'tmdb:watchlist', 'tmdb:user']]]
#     library: NotRequired[Sequence[Literal['library']]]
#     logs: NotRequired[Sequence[Literal['logs']]]


AddToServiceDefs: TypeAlias = 'dict[str, Sequence[ListPointer]]'
"""All services and theirs lists pointers. Keys are L() translated service names, values are available pointers for this service."""

AddToServices: TypeAlias = 'dict[str, Sequence[ServiceDescr]]'
"""All services and theirs lists descriptions. Keys are L() translated service names, values are available pointers for this service."""


class ListConvertRules(TypedDict):
    """
    Rules for convert refs. Missing key means 'ignore'.

    Dialog "add to…" can filter out those options by const.dialog.add_to.allowed_conversions.
    """

    show: NotRequired[Literal['ignore', 'season', 'episode']]
    season: NotRequired[Literal['ignore', 'show', 'episode']]
    episode: NotRequired[Literal['ignore', 'show', 'season']]
    collection: NotRequired[Literal['ignore', 'movie']]
    movie: NotRequired[Literal['ignore', 'collection']]


#: All available media conversions. Build from ListConvertRules. Can be filter-out by const.dialog.add_to.allowed_conversions.
AVALIABLE_CONVERSITION: dict[RefType, ListType] = {
    'collection': ListType.MOVIE,
    'movie': ListType.COLLECTION,
    'show': ListType.SEASON | ListType.EPISODE,
    'season': ListType.SHOW | ListType.EPISODE,
    'episode': ListType.SHOW | ListType.SEASON,
}


class AddToListCreateOptions(TypedDict):
    """Options for new list creation."""

    public: NotRequired[bool]


AddToListRemoveOptionsNames = Literal['items', 'lists']


class AddToListRemoveOptions(TypedDict):
    """Options for removing (items and lists)."""

    items: NotRequired[bool]  # default True
    lists: NotRequired[bool]  # default False


def converted_types(types: ListType = ListType.ALL) -> ListType:
    """Return real enabled conversions for `types`."""
    ref_types: set[RefType] = AVALIABLE_CONVERSITION.keys() & const.dialog.add_to.allowed_conversions.keys()
    allowed = ListType.NONE
    for ref_type in ref_types:
        if ListType.from_media_ref(ref_type) & types:
            allowed |= AVALIABLE_CONVERSITION[ref_type] & const.dialog.add_to.allowed_conversions[ref_type]
    return allowed


def convert_refs(refs: Iterable[FFRef], *, allowed: ListType, rules: ListConvertRules | str | None = None) -> Sequence[FFRef]:
    """
    Convert refs to allowed types with `convert` rules.

    Rules could be as string, ex.: 'collection:movie,show:episode'.
    """
    # TODO: split process into steps:
    #       1. scan for conversion and collect all items to need to be expanded
    #       2  get extra needed info from TMDB (in parallel)
    #       3. convert all items in order using collected data

    def convert(it: FFRef) -> Iterator[FFRef]:
        ref = it.ref
        real_type = ref.real_type
        typ = ListType.from_media_ref(real_type)
        if typ & allowed:
            yield it
        elif real_type == 'movie':
            if _rules.get('movie') == 'collection':
                ...  # Oh no! I have no collection here!
        elif real_type == 'show':
            if (rule := _rules.get('show')) == 'season':
                ...  # Oh no! I have no seasons here (no show content)!
            elif rule == 'episode':
                ...  # Oh no! I have no episodes here (no show content)!
        elif real_type == 'season':
            if (rule := _rules.get('season')) == 'show':
                if TYPE_CHECKING:
                    assert ref.show_ref
                yield ref.show_ref
            elif rule == 'episode':
                ...  # Oh no! I have no episodes here (no season content)!
        elif real_type == 'episode':
            if (rule := _rules.get('episode')) == 'show':
                if TYPE_CHECKING:
                    assert ref.show_ref
                yield ref.show_ref
            elif rule == 'season':
                if TYPE_CHECKING:
                    assert ref.season_ref
                yield ref.season_ref
        elif real_type == 'collection':
            if _rules.get('collection') == 'movie':
                ...  # Oh no! I have no movies here (no collection content)!

    # parse rules if string
    _rules: ListConvertRules
    if rules is None:
        _rules = {}
    elif isinstance(rules, str):
        _rules = {k: v for rr in (convert or '').split(',') for k, _, v in (rr.partition(':'),) if k and v}  # type: ignore[reportAssignmentType]
    else:
        _rules = rules

    # filter rules by allowed types, keys and values (except "ignore") are valid ListType type names
    _rules = {k: v for k, v in _rules.items() if ListType.from_media_ref(v) & allowed}  # type: ignore[reportAssignmentType]

    # convert
    return [x for ref in refs for x in convert(ref)]


@frozen
class ServiceDescr:
    """Service description for any list item add, including "add to" dialog."""

    #: Service pointer (name and optional section).
    pointer: ListPointer
    #: Options for new list creation, if supported by this service.
    #: If not None (even empty dict), then this service supports creating new lists.
    new_list_options: AddToListCreateOptions | None = field(default=None, kw_only=True)
    #: Supported list types for this service instance (pointer).
    types: ListType = field(default=ListType.NONE, kw_only=True)
    #: Supported new list types (default settings in new window).
    new_types: ListType = field(default=ListType.NONE, kw_only=True)
    #: Supported new list types, enabled to change.
    edit_types: ListType = field(default=ListType.NONE, kw_only=True)
    #: Supported new list types groups, select choices of set of types.  TODO: add support
    edit_type_choices: dict[str, ListType] = field(factory=dict, kw_only=True)
    #: Group names for all list pointers. Diffrent groups are separated (horizontal line) in Add-to dialog.
    group: str = field(default='', kw_only=True)
    #: When the pointer (service & section) is enabled in Add-to dialog.
    enabled: ShowCxtMenu = field(default=True, kw_only=True)
    #: Remove options (can remove items and/or lists).
    remove_options: AddToListRemoveOptions | None = field(default=None, kw_only=True)

    #: Default list types for this service, used if `types` is not set.
    DEFAULT_TYPES: ClassVar[ListType] = ListType.MAIN

    def __attrs_post_init__(self) -> None:
        if not self.types:
            object.__setattr__(self, 'types',  self.DEFAULT_TYPES)

    def is_enabled(self) -> bool:
        """Return True if this service is enabled in Add-to dialog."""
        from .settings import settings
        if isinstance(self.enabled, bool):
            return self.enabled
        return settings.eval(self.enabled)

    def lists(self) -> Sequence[FFItem]:
        """Return list FFItem items for `pointer` to show lists in a dialog."""
        def proc(it: FFItem) -> FFItem:
            if types := it.getProperty('list_type'):
                types = ListType.new(types)
            else:
                types = self.DEFAULT_TYPES
                it.setProperty('list_type', str(types))
            for typ in ListType.iter_single_flags():
                it.setProperty(f'type.{typ.attr}', 'true' if typ & types else 'false')
            # if not it.getProperty('service_description'):
            #     it.setProperty('service_description', self.__class__.__name__)
            if not it.getProperty('service'):
                it.setProperty('service', service)
            if not it.getProperty('section'):
                it.setProperty('section', section)
            if not it.getProperty('pointer'):
                it.setProperty('pointer', pointer)
            if not it.getProperty('group'):
                it.setProperty('group', lst.group if lst else '')
            return it

        pointer: ListPointer = self.pointer
        lst = LIST_POINTERS.get(pointer)
        service, _, section = pointer.partition(':')
        return tuple(proc(it) for it in self._lists(pointer))

    # Override this method in subclass.
    def _lists(self, pointer: ListPointer) -> Iterable[FFItem]:
        if False:  # force generator
            yield

    def create_enabled(self) -> bool:
        """Return True if this service supports creating new lists."""
        return self.new_list_options is not None

    def create(self, name: str, *, type: ListType = ListType.MEDIA, public: bool = False) -> bool:
        """Create new list in this service."""
        return False

    # Override this method in subclass.
    def _add_items(self, target: ListTarget, items: Iterable[FFRef]) -> int | None:
        return None

    # Override this method in subclass.
    def _remove_items(self, target: ListTarget, items: Iterable[FFRef]) -> int | None:
        return None

    # Override this method in subclass.
    def _remove_list(self, target: ListTarget) -> int | None:
        return None

    def add_items(self, target: ListTarget, items: Iterable[FFRef], *, quiet: bool = True) -> int | None:
        """
        Add items to this service.

        :param  target: target list to add items_str
        :param items: items to add
        :return: number of added items or None if not supported
        """
        return self._add_items(target, items)

    def add_folder_items(self, folder: FFItem, items: Iterable[FFRef]) -> int | None:
        """
        Add items to this service.

        :param  name: list name or location (e.g. 'favorites', 'watchlist')
        :param items: items to add
        :return: number of added items or None if not supported
        """
        return self._add_items(ListTarget.from_ffitem(folder), items)

    def has_remove_option(self, option: AddToListRemoveOptionsNames, target: ListTarget) -> bool:
        """
        Return True if this service supports removing items or lists.

        :param option: 'items' or 'lists'
        :return: True if supported, False otherwise
        """
        # special case, default True
        if option == 'items':
            return self.item_remove_enabled(target)
        # rest options are False by default
        if self.remove_options is None:
            return False
        return self.remove_options.get(option, False)

    def item_remove_enabled(self, target: ListTarget) -> bool:
        """Return True if this service supports removing items."""
        return self.remove_options is None or self.remove_options.get('items', True)
        # return self.remove_options is not None and self.remove_options.get('items', False)

    def remove_items(self, target: ListTarget, items: Iterable[FFRef], *, quiet: bool = True) -> int | None:
        """
        Remove items from this service.

        :param  target: target list to add items_str
        :param items: items to remove
        :return: number of added items or None if not supported
        """
        return self._remove_items(target, items)

    def list_remove_enabled(self, target: ListTarget) -> bool:
        """Return True if this service supports removing lists."""
        return self.remove_options is not None and self.remove_options.get('lists', False)

    def remove_list(self, target: ListTarget, *, quiet: bool = True) -> int | None:
        """
        Remove (delete) list from this service.

        :param  target: target list to remove
        :return: Number of removed lists (typically True), None if not supported
        """
        return self._remove_list(target)


class LibraryServiceDescr(ServiceDescr):

    DEFAULT_TYPES: ClassVar[ListType] = ListType.MAIN

    def _lists(self, pointer: ListPointer) -> Iterable[FFItem]:
        if pointer == 'library':
            yield FFItem(L(32541, 'Library'), properties={'list_type': format(ListType.MAIN), 'service': 'library'})

    def _add_items(self, target: ListTarget, items: Iterable[FFRef]) -> int | None:
        from ..service.client import service_client
        allowed = {'movie', 'show', 'season', 'episode'}
        items = tuple(it for it in items if it.ref.real_type in allowed)
        service_client.library_add(items, name=target.list or None, quiet=True)
        return len(items)


class LogsServiceDescr(ServiceDescr):

    DEFAULT_TYPES: ClassVar[ListType] = ListType.ALL

    def _lists(self, pointer: ListPointer) -> Iterable[FFItem]:
        if pointer == 'logs':
            yield FFItem(L(30359, 'Logs'), properties={'list_type': format(ListType.ALL)})

    def _add_items(self, target: ListTarget, items: Iterable[FFRef]) -> int | None:
        def dump_item(ref: FFRef) -> str:
            return f'  {ref}'
        items = tuple(items)
        items_str = '\n'.join(dump_item(it) for it in items)
        fflog.info(f'ADDING TO LOG... {len(items)} item(s):\n{items_str}')
        return len(items)


@frozen
class OwnServiceDescr(ServiceDescr):

    DEFAULT_TYPES: ClassVar[ListType] = ListType.MIXED

    def _lists(self, pointer: ListPointer) -> Iterable[FFItem]:
        from ..ff.ownlists import own_db
        art = {'thumb': art_path('services/own/main.png')}
        if pointer == 'own:favorites':
            if folder := own_db.folder(own_db.FAVORITES):
                yield FFItem(L(30348, 'Own Favorites'), properties={'list_type': format(folder.type), 'list_id': ':favorites'}, art=art)
        elif pointer == 'own:watchlist':
            if folder := own_db.folder(own_db.WATCHLIST):
                yield FFItem(L(30347, 'Own Watchlist'), properties={'list_type': format(folder.type), 'list_id': ':watchlist'}, art=art)
        elif pointer == 'own:user':
            if root := own_db.root():
                for ent in root.entries:
                    if name := ent.subfolder_name:
                        if TYPE_CHECKING:
                            assert ent.subfolder is not None
                        yield FFItem(name, properties={'list_type': format(ent.subfolder.type)}, art=art)

    def create(self, name: str, *, type: ListType = ListType.MIXED, public: bool = False) -> bool:
        from ..ff.ownlists import own_db
        try:
            own_db.create_list(name, type=type)
            return True
        except Exception:
            return False

    def _list(self, target: ListTarget) -> str | None:
        from ..ff.ownlists import own_db
        list_names = {
            'favorites': own_db.FAVORITES,
            'watchlist': own_db.WATCHLIST,
            'collection': own_db.COLLECTION,
            'user': target.list,
        }
        return list_names.get(target.section)

    def _add_items(self, target: ListTarget, items: Iterable[FFRef]) -> int | None:
        from ..ff.ownlists import own_db
        if list_id := self._list(target):
            return own_db.list_add(list_id, items)
        return None  # not supported

        # if target.section == 'favorites':
        #     list_id = own_db.FAVORITES
        # elif target.section == 'watchlist':
        #     list_id = own_db.WATCHLIST
        # elif target.section == 'user':
        #     list_id = target.list
        # else:
        #     return None  # not supported
        # return own_db.list_add(list_id, items)

    def _remove_list(self, target: ListTarget) -> int | None:
        from ..ff.ownlists import own_db
        if list_id := self._list(target):
            return own_db.delete_list(list_id)
        return None  # not supported

    def _remove_items(self, target: ListTarget, items: Iterable[FFRef]) -> int | None:
        from ..ff.ownlists import own_db
        if list_id := self._list(target):
            return own_db.remove_items(list_id, items)
        return None  # not supported


@frozen
class TmdbServiceDescr(ServiceDescr):

    DEFAULT_TYPES: ClassVar[ListType] = ListType.MAIN

    def _lists(self, pointer: ListPointer) -> Iterable[FFItem]:
        from .tmdb import tmdb
        list_type = str(ListType.MAIN)
        art = {'thumb': art_path('services/tmdb/main.png')}
        if pointer == 'tmdb:favorites':
            yield FFItem(L(32803, 'TMDB Favorites'), properties={'list_type': list_type, 'list_id': 'favorite'}, art=art)
        elif pointer == 'tmdb:watchlist':
            yield FFItem(L(32802, 'TMDB Watchlist'), properties={'list_type': list_type, 'list_id': 'watchlist'}, art=art)
        elif pointer == 'tmdb:user':
            for it in sorted(tmdb.user_lists(), key=lambda it: it.label.lower()):
                it.setProperty('list_type', list_type)
                it.setArt(art)
                yield it

    def create(self, name: str, *, type: ListType = ListType.MAIN, public: bool = False) -> bool:
        from .tmdb import tmdb
        return bool(tmdb.create_user_list(name, public=public))

    def _add_items(self, target: ListTarget, items: Iterable[FFRef]) -> int | None:
        from .tmdb import tmdb
        if target.section == 'favorites':
            return tmdb.add_items_to_general_list('favorite', items)
        if target.section == 'watchlist':
            return tmdb.add_items_to_general_list('watchlist', items)
        if target.section == 'user' and target.list.isdecimal():
            return tmdb.add_to_user_list(int(target.list), items)
        return None  # not supported

    def _remove_list(self, target: ListTarget) -> int | None:
        from .tmdb import tmdb
        if target.section == 'user' and target.list.isdecimal():
            return tmdb.delete_user_list(int(target.list))

    def _remove_items(self, target: ListTarget, items: Iterable[FFRef]) -> int | None:
        from .tmdb import tmdb
        if target.section == 'favorites':
            return tmdb.remove_items_from_general_list('favorite', items)
        if target.section == 'watchlist':
            return tmdb.remove_items_from_general_list('watchlist', items)
        if target.section == 'user' and target.list.isdecimal():
            return tmdb.remove_from_user_list(int(target.list), items)
        return None  # not supported


@frozen
class TraktServiceDescr(ServiceDescr):

    DEFAULT_TYPES: ClassVar[ListType] = ListType.MEDIA | ListType.PERSON

    def _lists(self, pointer: ListPointer) -> Iterable[FFItem]:
        from .trakt import trakt
        art = {'thumb': art_path('services/trakt/main.png')}
        if pointer == 'trakt:favorites':
            yield FFItem(L(30360, 'Trakt Favorites'), properties={'list_type': str(ListType.MAIN)}, art=art)
        elif pointer == 'trakt:watchlist':
            yield FFItem(L(30361, 'Trakt Watchlist'), properties={'list_type': str(ListType.MEDIA)}, art=art)
        elif pointer == 'trakt:collection':
            yield FFItem(L(30362, 'Trakt Collection'), properties={'list_type': str(ListType.MEDIA)}, art=art)
        elif pointer == 'trakt:user':
            for it in sorted(trakt.user_lists(), key=lambda it: it.label.lower()):
                it.setArt(art)
                yield it

    def create(self, name: str, *, type: ListType = ListType.MAIN, public: bool = False) -> bool:
        from .trakt import trakt
        privacy = 'public' if public else 'private'
        return bool(trakt.create_user_list(name, privacy=privacy))

    def _add_items(self, target: ListTarget, items: Iterable[FFRef]) -> int | None:
        from .trakt import trakt
        if target.section in ('favorites', 'watchlist', 'collection'):
            ok, num = trakt.add_to_generic_list(target.section, items)
            return num
            # return num if ok else None
        if target.section == 'user':
            ok, num = trakt.add_to_user_list(target.list, items)
            return num
        return None  # not supported

    def _remove_list(self, target: ListTarget) -> int | None:
        from .trakt import trakt
        if target.section == 'user' and target.list:
            return trakt.delete_user_list(target.list)

    def _remove_items(self, target: ListTarget, items: Iterable[FFRef]) -> int | None:
        from .trakt import trakt
        if target.section in ('favorites', 'watchlist', 'collection'):
            ok, num = trakt.remove_from_generic_list(target.section, items)
            return num
            # return num if ok else None
        if target.section == 'user':
            ok, num = trakt.remove_from_user_list(target.list, items)
            return num
        return None  # not supported


@frozen
class MdblistServiceDescr(ServiceDescr):

    DEFAULT_TYPES: ClassVar[ListType] = ListType.MAIN

    def _lists(self, pointer: ListPointer) -> Iterable[FFItem]:
        from ..api.mdblist import mdblist
        art = {'thumb': art_path('services/mdblist/main.png')}
        if pointer == 'mdblist:watchlist':
            yield FFItem(L(30363, 'MDBList Watchlist'), properties={'list_type': str(ListType.MAIN), 'list_id': 'watchlist'}, art=art)
        elif pointer == 'mdblist:user':
            list_type = str(ListType.MEDIA)
            for it in sorted(mdblist.user_lists(static=True), key=lambda it: it.label.lower()):
                it.setProperty('list_type', list_type)
                it.setArt(art)
                yield it

    # def create(self, name: str, *, type: ListType = ListType.MAIN, public: bool = False) -> bool:
    #     from ..api.mdblist import mdblist
    #     return bool(mdblist.create_user_list(name, public=public))

    def _add_items(self, target: ListTarget, items: Iterable[FFRef]) -> int | None:
        from ..api.mdblist import mdblist
        if target.section == 'watchlist':
            ok, num = mdblist.add_to_watchlist(items)
            return num
        if target.section == 'user' and target.list.isdecimal():
            ok, num = mdblist.add_to_user_list(int(target.list), items)
            return num
        return None  # not supported

    def _remove_items(self, target: ListTarget, items: Iterable[FFRef]) -> int | None:
        from ..api.mdblist import mdblist
        if target.section == 'watchlist':
            ok, num = mdblist.remove_from_watchlist(items)
            return num
        if target.section == 'user' and target.list.isdecimal():
            ok, num = mdblist.remove_from_user_list(int(target.list), items)
            return num
        return None  # not supported


@frozen
class ImdbServiceDescr(ServiceDescr):

    DEFAULT_TYPES: ClassVar[ListType] = ListType.MOVIE | ListType.SHOW | ListType.EPISODE | ListType.PERSON

    @frozen(kw_only=True)
    class NewListHack:
        id: str
        name: str
        type: ListType
        timestamp: float = field(factory=lambda: monotonic())

        def to_ffitem(self) -> FFItem:
            it = FFItem(self.name, properties={'list_type': str(self.type), 'list_id': self.id}, art={'thumb': art_path('services/imdb/main.png')})
            it.title = self.name
            return it

    _NEW_LIST_DELAY: ClassVar[float] = 5.0  # seconds to wait before new list appears in API (at least 3 seconds, but to be safe, set 5 seconds)
    _NEW_LIST_ADDED: ClassVar[list[ImdbServiceDescr.NewListHack]] = []

    @classmethod
    def _clear_expired_new_list_hacks(cls) -> None:
        """Clear expired new list hacks."""
        now = monotonic()
        cls._NEW_LIST_ADDED = [hack for hack in cls._NEW_LIST_ADDED if now - hack.timestamp < cls._NEW_LIST_DELAY]

    def _lists(self, pointer: ListPointer) -> Iterable[FFItem]:
        from ..api.imdb import imdb_api
        # titles_type = str(ListType.MAIN | ListType.EPISODE)
        # people_type = str(ListType.PERSON)
        art = {'thumb': art_path('services/imdb/main.png')}
        if pointer == 'imdb:watchlist':
            yield FFItem(L(30346, 'IMDb Watchlist'), properties={'list_type': str(ListType.MAIN), 'list_id': 'watchlist'}, art=art)
        elif pointer == 'imdb:user':
            self._clear_expired_new_list_hacks()
            # real API lists
            items = imdb_api.user_lists()
            # temporary hacks for new lists, because IMDB API is very slow to update new lists
            items.extend(hack.to_ffitem() for hack in self._NEW_LIST_ADDED)
            # use all available lists
            for it in sorted(items, key=lambda it: it.label.lower()):
                # it.setProperty('list_type', people_type if it.ref.type == 'person' else titles_type)
                it.setArt(art)
                yield it

    def _bulk_result(self, result: dict[str, bool]) -> int | None:
        return sum(result.values())

    def _imdb_items(self, items: Iterable[FFRef]) -> list[str]:
        from .info import ffinfo
        refs: dict[MediaRef, AllIdsDict] = ffinfo.lookup_ids(items, {'imdb'})
        return [imdb for it in refs.values() if (imdb := it.get('imdb'))]

    def create(self, name: str, *, type: ListType = ListType.MAIN, public: bool = False) -> bool:
        from ..api.imdb import imdb_api
        created = imdb_api.create_list(name, list_type=type, public=public)
        if created:
            # hack: because IMDB API is very slow to update new lists, we add new list to temporary list with timestamp
            self._NEW_LIST_ADDED.append(self.NewListHack(id=created, name=name, type=type))
        return bool(created)

    def _add_items(self, target: ListTarget, items: Iterable[FFRef]) -> int | None:
        from ..api.imdb import imdb_api
        imdb_items = self._imdb_items(items)
        if target.section == 'watchlist':
            return self._bulk_result(imdb_api.add_to_watchlist_bulk(imdb_items))
        if target.section == 'user' and target.list.startswith('ls') and target.list[2:].isdecimal():
            return self._bulk_result(imdb_api.add_to_list_bulk(target.list, imdb_items))
        return None  # not supported

    def item_remove_enabled(self, target: ListTarget) -> bool:
        """Only when authenticated - removal mutations require the at-main cookie and would just fail."""
        from ..api.imdb import imdb_api
        return imdb_api.authenticated and super().item_remove_enabled(target)

    def list_remove_enabled(self, target: ListTarget) -> bool:
        """Only when authenticated - deleteList requires the at-main cookie and would just fail."""
        from ..api.imdb import imdb_api
        return imdb_api.authenticated and super().list_remove_enabled(target)

    def _remove_list(self, target: ListTarget) -> int | None:
        from ..api.imdb import imdb_api
        if target.section == 'user' and re.fullmatch(r'ls\d+', target.list):
            return imdb_api.delete_list(target.list)
        return None  # not supported

    def _remove_items(self, target: ListTarget, items: Iterable[FFRef]) -> int | None:
        from ..api.imdb import imdb_api
        imdb_items = self._imdb_items(items)
        if target.section == 'watchlist':
            return self._bulk_result(imdb_api.remove_from_watchlist_bulk(imdb_items))
        if target.section == 'user' and target.list.startswith('ls') and target.list[2:].isdecimal():
            return self._bulk_result(imdb_api.remove_from_list_bulk(target.list, imdb_items))
        return None  # not supported


# Define all available list services.
# remove_options: 'items' is True by default, 'lists' is False by default.
LIST_POINTERS: dict[ListPointer, ServiceDescr] = {srv.pointer: srv for srv in (
    ServiceDescr('local'),  # dummy pointer, empty list, do NOT use it
    LibraryServiceDescr('library',           group='library',    enabled='enable_library',
                        remove_options={'items': False}),
    LogsServiceDescr('logs',                 group='logs',       enabled='const.debug.add_to_logs',
                     remove_options={'items': False}),

    OwnServiceDescr('own:favorites',         group='own:generic', enabled='ListsInfo.own_enabled()'),
    OwnServiceDescr('own:watchlist',         group='own:generic', enabled='ListsInfo.own_enabled()'),
    OwnServiceDescr('own:collection',        group='own:generic', enabled='ListsInfo.own_enabled()'),
    OwnServiceDescr('own:user',              group='own:user',    enabled='ListsInfo.own_enabled()',
                    new_list_options={}, edit_types=ListType.ALL, remove_options={'lists': True}),

    TmdbServiceDescr('tmdb:favorites',       group='tmdb:generic',  enabled='ListsInfo.tmdb_enabled()'),
    TmdbServiceDescr('tmdb:watchlist',       group='tmdb:generic',  enabled='ListsInfo.tmdb_enabled()'),
    TmdbServiceDescr('tmdb:user',            group='tmdb:user',     enabled='ListsInfo.tmdb_enabled()',
                     new_list_options={'public': True}, remove_options={'lists': True}),

    TraktServiceDescr('trakt:favorites',     group='trakt:generic', enabled='ListsInfo.trakt_enabled()'),
    TraktServiceDescr('trakt:watchlist',     group='trakt:generic', enabled='ListsInfo.trakt_enabled()'),
    TraktServiceDescr('trakt:collection',    group='trakt:generic', enabled='ListsInfo.trakt_enabled()'),
    TraktServiceDescr('trakt:user',          group='trakt:user',    enabled='ListsInfo.trakt_enabled()',
                      new_list_options={'public': True}, new_types=ListType.MIXED, remove_options={'lists': True}),

    MdblistServiceDescr('mdblist:watchlist', group='mdblist:generic', enabled='ListsInfo.mdblist_enabled(premium=True)'),
    MdblistServiceDescr('mdblist:user',      group='mdblist:user',    enabled='ListsInfo.mdblist_enabled(premium=True)'),  # no create in API !!!

    ImdbServiceDescr('imdb:watchlist',       group='imdb:generic',  enabled='ListsInfo.imdb_enabled()'),
    ImdbServiceDescr('imdb:user',            group='imdb:user',     enabled='ListsInfo.imdb_enabled()',
                     new_list_options={'public': True}, remove_options={'lists': True},
                     edit_type_choices={L(30632, 'Titles'): ListType.MAIN | ListType.EPISODE, L(30633, 'People'): ListType.PERSON}),  # TODO: add support for edit_type_choices in dialog
)}


def art_path(fname: str) -> str:
    return str(Path(control.art_path) / fname)


#: Pointers offered by pickable_lists() for the "sync arbitrary lists to library" picker.
#: 'own:collection' is deliberately omitted - OwnServiceDescr._lists() has no case for it (yields
#: nothing), even though the underlying own_db collection folder exists.
#: 'trakt:watchlist' is filtered out at runtime unless const.library.picker.show_trakt_watchlist.
_PICKABLE_POINTERS: tuple[ListPointer, ...] = (
    'trakt:collection', 'trakt:favorites', 'trakt:watchlist', 'trakt:user',
    'tmdb:favorites', 'tmdb:watchlist', 'tmdb:user',
    'mdblist:watchlist', 'mdblist:user',
    'own:favorites', 'own:watchlist', 'own:user',
    'imdb:watchlist', 'imdb:user',
)


class PickableListEntry(TypedDict):
    #: `service:section:list` token, matching cdefs.ListTarget - what gets stored/parsed back.
    token: str
    #: Display label, e.g. "Trakt: Watchlist" or "FanFilm: My own list".
    label: str
    #: Service icon path (thumb art already set by ServiceDescr._lists() on each FFItem).
    icon: str


def pickable_lists() -> list[PickableListEntry]:
    """Return an entry for every enabled list (fixed + custom) across all services.

    Meant for a "pick list(s) to sync to library" multiselect dialog.
    """
    from .control import addonName

    # Every entry is labeled uniformly as "{Service}: {List name}" - no exceptions, so a fixed
    # list (favorites/watchlist/collection) and a custom user list never look inconsistent next
    # to each other. Fixed sections use a generic (service-agnostic) translated word instead of
    # each service's own baked-in label (e.g. "Trakt Watchlist"), since those aren't uniform
    # across services/locales (own: "Lista ulubionych" doesn't even mention the addon).
    service_labels: dict[str, str] = {
        'trakt': L(30356, 'Trakt'),
        'tmdb': L(32775, 'TMDB'),
        'mdblist': L(30357, 'MDBList'),
        'own': addonName(),
        'imdb': L(30630, 'IMDb'),
    }
    section_labels: dict[str, str] = {
        'collection': L(32032, 'Collection'),
        'favorites': L(30146, 'Favorites'),
        'watchlist': L(32033, 'Watchlist'),
    }
    entries: list[PickableListEntry] = []
    for ptr in _PICKABLE_POINTERS:
        srv = LIST_POINTERS.get(ptr)
        if not srv or not srv.is_enabled():
            continue
        for it in srv.lists():
            target = ListTarget.from_ffitem(it)
            is_trakt_watchlist = target.service == 'trakt' and target.section == 'watchlist'
            if is_trakt_watchlist and not const.library.picker.show_trakt_watchlist:
                continue
            token: str = f'{target.service}:{target.section}:{target.list}'
            service_label: str = service_labels.get(target.service, target.service)
            list_label: str = section_labels.get(target.section, it.getLabel())
            entries.append({
                'token': token,
                'label': f'{service_label}: {list_label}',
                'icon': it.getArt('thumb'),
            })
    return entries


#: Setting holding the comma-separated, quoted list of tokens picked via pickable_lists().
_SELECTED_LISTS_SETTING: str = 'library.lists.selected'

#: Before pickable_lists() existed, each service had its own fixed auto-sync target with its own
#: on/off + schedule setting (autoXOnStart/schedXTime). Those settings were dropped from
#: settings.xml, but Kodi keeps their values in the user's profile regardless. Maps each pair to
#: the equivalent pointer in the new system, so an upgrading user's active legacy syncs aren't
#: silently dropped just because they never opened the new picker.
_LEGACY_AUTO_SYNC: tuple[tuple[str, str, str, ListPointer], ...] = (
    ('autoTraktOnStart', 'schedTraktTime', 'trakt', 'trakt:collection'),
    ('autoTmdbOnStart', 'schedTmdbTime', 'tmdb', 'tmdb:favorites'),
    ('autoIMdbOnStart', 'schedIMdbTime', 'imdb', 'imdb:watchlist'),
    ('autoMdbListOnStart', 'schedMdbListTime', 'mdblist', 'mdblist:watchlist'),
    ('autoOwnOnStart', 'schedOwnTime', 'own', 'own:favorites'),
)


def _migrate_legacy_auto_sync_selection() -> set[str]:
    """Build an initial selection from whichever legacy per-service auto-syncs were both active
    and are still authorized today - see _LEGACY_AUTO_SYNC. Only called when nothing has been
    picked yet, so a user who deliberately never enabled any of them just gets an empty result.
    """
    from .settings import settings
    from ..indexers.lists import ListsInfo
    lists_info = ListsInfo(public=True)
    entries = pickable_lists()
    tokens: set[str] = set()
    for on_start_setting, sched_setting, service, pointer in _LEGACY_AUTO_SYNC:
        # these settings no longer exist in settings.xml - reading them through the typed
        # getBool()/getInt() raises ("Invalid setting type") since there's no schema entry left.
        # get_string() goes through the old, unvalidated Addon().getSetting() instead, which
        # just returns '' for an id it doesn't know (a brand new user never had these at all).
        on_start_raw: str = settings.get_string(on_start_setting, k19log=False)
        sched_raw: str = settings.get_string(sched_setting, k19log=False)
        was_active: bool = on_start_raw.lower() == 'true' or (sched_raw.isdigit() and int(sched_raw) > 0)
        if not was_active or not lists_info.enabled(service):
            continue
        prefix: str = f'{pointer}:'
        for entry in entries:
            if entry['token'].startswith(prefix):
                tokens.add(entry['token'])
                break
    return tokens


#: Hidden marker for "the legacy-settings migration already ran once". Without it, a user who
#: deliberately clears their whole selection back to empty would have it silently repopulated
#: from _LEGACY_AUTO_SYNC on the next read - empty must be able to mean "sync nothing", not just
#: "never touched yet".
_MIGRATION_DONE_SETTING: str = 'library.lists.migrated'


def selected_list_tokens() -> set[str]:
    """Return the currently picked list tokens (see pickable_lists()) for library auto-sync."""
    from urllib.parse import unquote
    from .settings import settings
    # quote()d on save (a token, e.g. an own custom list name, may contain a literal ',' or
    # ':'); unquote() is a no-op on plain (pre-existing, unquoted) tokens.
    raw = settings.getString(_SELECTED_LISTS_SETTING)
    tokens = {unquote(tok) for tok in raw.split(',') if tok}
    if not tokens and settings.get_string(_MIGRATION_DONE_SETTING, k19log=False) != 'true':
        tokens = _migrate_legacy_auto_sync_selection()
        settings.set_string(_MIGRATION_DONE_SETTING, 'true')
        if tokens:
            save_selected_list_tokens(tokens)
    return tokens


def save_selected_list_tokens(tokens: Iterable[str]) -> None:
    """Save the picked list tokens (see pickable_lists()) for library auto-sync."""
    from urllib.parse import quote
    from .settings import settings
    settings.setString(_SELECTED_LISTS_SETTING, ','.join(quote(tok, safe='') for tok in tokens))


def pickable_lists_with_preselect() -> tuple[list[PickableListEntry], list[int]]:
    """Return (entries, preselect) - pickable_lists() plus the indexes already selected.

    Meant to directly feed a multiselect dialog's `options`/`preselect` arguments.
    """
    entries = pickable_lists()
    current = selected_list_tokens()
    preselect = [i for i, entry in enumerate(entries) if entry['token'] in current]
    return entries, preselect


def pickable_list_items(entries: Iterable[PickableListEntry]) -> list[ListItem]:
    """Convert pickable_lists() entries into ready-to-show Dialog().multiselect() ListItems."""
    from xbmcgui import ListItem
    items: list[ListItem] = []
    for entry in entries:
        item = ListItem(label=entry['label'])
        if entry['icon']:
            item.setArt({'icon': entry['icon'], 'thumb': entry['icon']})
        items.append(item)
    return items


def pick_lists_heading(*, selected: int, total: int) -> str:
    """Build the "pick lists to sync" dialog/info heading, e.g. "... - selected 3 of 17"."""
    title = L(30646, 'Select lists to sync to library')
    if not total:
        return title
    return L(30650, '{title} - selected {selected} of {total}').format(title=title, selected=selected, total=total)


def list_target_items(target: ListTarget) -> Iterable[FFRef] | None:
    """Return an iterable of items for one picked list (any service), or None if unsupported.

    Used both by the actual library sync (lib.service.main sync_lists_lib) and by a "preview"
    (count only, no writing) - a plain read, no library/service dependency either way.
    """
    from .settings import settings
    if target.service == 'trakt':
        from .trakt import trakt
        if target.section == 'collection':
            return chain(trakt.user_collection('movie'), trakt.user_collection('show'))
        if target.section in ('favorites', 'watchlist'):
            # NOTE: trakt auto-removes an item from the watchlist once you start watching it,
            # so unlike collection, a show may stop getting new-episode updates mid-season.
            return trakt.user_generic_list(target.section, media_type=None)
        if target.section == 'user' and target.list:
            from ..api import ALL_PAGES
            return trakt.user_list_items(target.list, media_type=None, page=ALL_PAGES)
    elif target.service == 'tmdb':
        from ..api import depaginate
        from .tmdb import tmdb
        # tmdb (unlike trakt) doesn't paginate itself - it needs the generic depaginate()
        # wrapper, and results must be materialized eagerly inside the `with` block.
        with depaginate(tmdb) as api:
            if target.list in ('favorite', 'watchlist'):
                return list(chain(api.user_general_lists(list_type=target.list, type='movie'),
                                  api.user_general_lists(list_type=target.list, type='show')))
            if target.section == 'user' and target.list.isdecimal():
                return list(api.user_list_items(int(target.list)))
    elif target.service == 'mdblist':
        from ..api.mdblist import mdblist
        if target.list == 'watchlist':
            return mdblist.watchlist_items()
        if target.section == 'user' and target.list.isdecimal():
            return mdblist.list_items(int(target.list))
    elif target.service == 'own':
        from .ownlists import own_db
        return own_db.list(target.list or f':{target.section}')
    elif target.service == 'imdb':
        from ..api import depaginate
        from ..api.imdb import ImdbScraper
        with depaginate(ImdbScraper()) as api:
            if target.section == 'watchlist':
                if settings.getString('imdb.at-main'):
                    return api.watch_list(None)
                if users := settings.getString('imdb.user'):
                    # materialize eagerly - depagination context is only valid inside this `with` block
                    return list(chain.from_iterable(api.watch_list(user) for user in users.split(',')))
            elif target.section == 'user' and target.list:
                return list(api.list(target.list, media_type=None))
    fflog(f'[LIB] Unsupported list target {target!r}, skipping.')
    return None


def apply_list_selection(entries: list[PickableListEntry], chosen: list[int]) -> None:
    """Save a pickable_lists_with_preselect() multiselect result.

    Deliberately live/exact: replaces the whole saved selection with exactly what's checked
    right now. If a service is currently deauthorized (so its lists aren't shown at all), any
    previously-picked tokens for it are dropped, not silently kept around for later.
    """
    save_selected_list_tokens(entries[i]['token'] for i in chosen)


def run_pick_lists_dialog() -> None:
    """Show the "pick lists to sync" dialog and save the picked selection."""
    from xbmcgui import Dialog
    from .control import infoDialog, busy_section

    with busy_section():
        entries, preselect = pickable_lists_with_preselect()
    heading = pick_lists_heading(selected=len(preselect), total=len(entries))
    if not entries:
        infoDialog(heading)
        return
    options = pickable_list_items(entries)
    # useDetails=True - without it Kodi's simple multiselect layout has no image control at
    # all, so the icons set on each ListItem in pickable_list_items() would be silently ignored.
    chosen: list[int] | None = Dialog().multiselect(heading, options, preselect=preselect, useDetails=True)
    if chosen is None:
        return
    apply_list_selection(entries, chosen)


def run_preview_lists_dialog() -> None:
    """Show item counts for the currently picked lists - a dry-run, nothing gets written."""
    from xbmcgui import Dialog
    from cdefs import ListTarget
    from .control import infoDialog
    from ..indexers.lists import ListsInfo

    heading = L(30648, 'Preview selected lists')
    tokens = selected_list_tokens()
    if not tokens:
        infoDialog(heading)
        return
    labels: dict[str, str] = {entry['token']: entry['label'] for entry in pickable_lists()}
    # public=True: allows a public-only imdb.user config (no private at-main cookie) to still count.
    lists_info = ListsInfo(public=True)
    lines: list[str] = []
    for tok in sorted(tokens):
        service, _, rest = tok.partition(':')
        section, _, list_id = rest.partition(':')
        label = labels.get(tok, tok)
        if not lists_info.enabled(service):
            lines.append(f'{label}: {L("not authorized")}')
            continue
        target = ListTarget(service=service, section=section, list=list_id)  # type: ignore[arg-type]
        try:
            items = list_target_items(target)
        except Exception:
            fflog_exc()
            lines.append(f'{label}: {L("error")}')
            continue
        if items is None:
            lines.append(f'{label}: {L("unsupported")}')
            continue
        movies = shows = other = 0
        for it in items:
            if it.type == 'movie':
                movies += 1
            elif it.type == 'show':
                shows += 1
            else:
                other += 1
        parts = [f'{word}: {n}' for n, word in (
            (movies, L(32543, 'Movies')), (shows, L(32544, 'TV Shows')), (other, L(30651, 'other')),
        ) if n]
        lines.append(f'{label}: {", ".join(parts) if parts else 0}')
    Dialog().textviewer(heading, '\n'.join(lines))
