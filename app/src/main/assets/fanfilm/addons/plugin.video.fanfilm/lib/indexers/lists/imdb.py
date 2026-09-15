from typing import Optional, Union
from typing_extensions import Literal
from ..core import Indexer
from ..folder import item_folder_route, pagination, ApiPage, FolderRequest, list_directory, Folder
from ...ff.routing import route, info_for, PathArg
from ...ff.menu import directory, KodiDirectory
from ...ff.settings import settings
from ...api.imdb import imdb_api
from ...defs import Pagina, MediaRef
from ...kolang import L
# from ...ff.log_utils import fflog
from const import const


ListMediaType = Literal['movie', 'show', 'episode', 'person']
UserRatingType = Literal['movie', 'show', 'episode', 'all']


class ImdbLists(Indexer):

    @route('/')
    def home(self) -> None:
        """User IMDB lists."""
        with directory(view=const.indexer.lists_view) as kdir:
            # kdir.folder(L(30146, 'Favorites'), self.favorites, thumb='DefaultMovies.png')
            kdir.folder(L(32033, 'Watchlist'), self.watchlists, thumb='services/imdb/watchlist.png')
            kdir.folder(L(30546, 'Ratings'), self.rated, thumb='services/imdb/ratings.png')
            kdir.folder(L(30149, 'My Lists'), self.mine, thumb='services/imdb/my.png')

    @route
    def watchlists(self) -> None:
        with list_directory(view=const.indexer.lists_view) as kdir:
            if const.indexer.imdb.watchlist.mixed:
                kdir.folder(L(30159, 'Mixed Watchlist'), info_for(self.watchlist), thumb='services/imdb/watchlist.png')
            kdir.folder(L(30160, 'Watchlist Movies'), info_for(self.watchlist, media='movie'), thumb='services/imdb/watchlist.png')
            kdir.folder(L(30161, 'Watchlist TV Shows'), info_for(self.watchlist, media='show'), thumb='services/imdb/watchlist.png')

    @item_folder_route('watchlist/{__media}', list_spec='imdb:watchlist')
    def watchlist(self,
                  req: FolderRequest,
                  /, *,
                  user: Optional[str] = None,
                  media: Optional[Literal['movie', 'show']] = None,
                  page: int = 1,
                  ) -> Folder:
        """Return IMDB watchlist items."""
        if not user:
            user = settings.getString('imdb.user')
        items = imdb_api.watch_list(user, media_type=media)
        if req.as_folder:
            items = Pagina(items, page=page, limit=const.indexer.imdb.page_size)
        # nothing ever calls watchlist() with an explicit `user` (always defaults to imdb.user), so it's
        # always *my* watchlist - offering to remove without a cookie would just fail
        return Folder(items, list_spec=imdb_api.authenticated)

    @route('/rated')
    @route('/rated/{media}')
    def rated(self, *, media: Optional[UserRatingType] = None) -> None:
        """User IMDb rated sub-menu."""
        with list_directory(view=const.indexer.lists_view) as kdir:
            if media is None:
                kdir.folder(L(30547, 'Rated movies'), info_for(self.rated, media='movie'), thumb='services/imdb/ratings.png')
                kdir.folder(L(30548, 'Rated tvshows'), info_for(self.rated, media='show'), thumb='services/imdb/ratings.png')
                kdir.folder(L(30550, 'Rated episodes'), info_for(self.rated, media='episode'), thumb='services/imdb/ratings.png')
                kdir.folder(L(30551, 'Rated (mixed)'), info_for(self.rated, media='all'), thumb='services/imdb/ratings.png')
            else:
                from ...ff.ratings import NORM_RANGE
                kdir.folder(L(30552, 'All rated'), info_for(self.rated_items, media=media, rating='-'), thumb='services/imdb/ratings.png')
                for rating in reversed(NORM_RANGE):
                    label = L(30553, 'Rated {rating}').format(rating=rating)
                    kdir.folder(label, info_for(self.rated_items, media=media, rating=rating), thumb='services/imdb/ratings.png')

    @item_folder_route('/rated/{media}/{rating}/{__page}')
    def rated_items(self, *,
                    media: Optional[UserRatingType],
                    rating: Union[int, Literal['-'], None],
                    page: int = 1,
                    ):
        """Show IMDb rated items by rating."""
        from ...ff.ratings import all_rating_services
        service = all_rating_services['imdb']
        if rating == '-':
            rating = None
        media_type = None if media == 'all' else media
        items = imdb_api.user_ratings(media_type=media_type, min_rating=rating, max_rating=rating, page=page)
        for it in items:
            it.vtag.setUserRating(service.from_service_rating(int(it.getProperty('imdb.user_rating') or 0)))
            if rating is None:
                it.role = L(30554, 'Rating: {rating}').format(rating=it.vtag.getUserRating())
        return items

    def _user_lists(self, user: str, *, media: Optional[ListMediaType] = None, page: int = 1) -> None:
        items = imdb_api.user_lists(user, page=page, list_type=media)
        with list_directory(items, view=const.indexer.imdb.mine.view) as kdir:
            for it in items:
                # it.mode = it.Mode.Folder
                kdir.add(it, url=info_for(self.user_list, list_id=it.vtag.getIMDBNumber(), media=media), thumb='services/imdb/lists.png')

    @route('/mine/{__page}')
    def mine(self, *, media: Optional[ListMediaType] = None, page: int = 1) -> None:
        """Show IMDB user or single user lists (`page` is used only for single user lists)."""
        users = [u for val in settings.getString('imdb.user').split(',') if (u := val.strip())]
        if settings.getString('imdb.at-main'):
            users.insert(0, 'me')
        users = list(dict.fromkeys(users))  # remove duplicates, keep order
        if not users:
            self.no_content()
        elif len(users) == 1:
            self._user_lists(users[0], media=media, page=page)
        else:
            with list_directory(view=const.indexer.imdb.mine.view) as kdir:
                imdb_ids = set()
                for it in imdb_api.user_info_list(users):
                    if it is not None and (usr := it.vtag.getIMDBNumber()):
                        if usr not in imdb_ids:
                            imdb_ids.add(usr)
                            if it.getProperty('imdb.access') == 'private':
                                usr = 'me'
                            kdir.add(it, url=info_for(self.user, user=usr, media=media), thumb='services/imdb/user.png')

    @route('/mine/{user}/{__page}')
    def user(self, *, media: Optional[ListMediaType] = None, user: str, page: int = 1) -> None:
        """Show IMDB user lists."""
        self._user_lists(user, media=media, page=page)

    @item_folder_route('list/{list_id}', list_spec='imdb:user:{list_id}')
    # @item_folder_route('list/{list_id}', list_spec=...)
    def user_list(self, *,
                  list_id: str,
                  media: Optional[Literal['movie', 'show']] = None,
                  page: PathArg[int] = 1,
                  ) -> Folder:
        """Return the list items."""
        items = imdb_api.list(list_id, media_type=media, page=page)
        return Folder(items, list_spec=items.mine)
