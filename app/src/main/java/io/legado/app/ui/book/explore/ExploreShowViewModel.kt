package io.legado.app.ui.book.explore

import android.app.Application
import android.content.Intent
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import io.legado.app.BuildConfig
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.SearchBook
import io.legado.app.help.book.isNotShelf
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.printOnDebug
import io.legado.app.utils.stackTraceStr
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.mapLatest
import java.util.concurrent.ConcurrentHashMap

@OptIn(ExperimentalCoroutinesApi::class)
class ExploreShowViewModel(application: Application) : BaseViewModel(application) {

    val bookshelf: MutableSet<String> = ConcurrentHashMap.newKeySet()
    val upAdapterLiveData = MutableLiveData<String>()
    val booksData = MutableLiveData<List<SearchBook>>()
    val errorLiveData = MutableLiveData<String>()

    private var bookSource: BookSource? = null
    private var exploreUrl: String? = null

    // 当前页码（从 1 开始，用于 UI 显示）
    var currentPage: Int = 1
        private set

    private var books = linkedSetOf<SearchBook>()

    init {
        execute {
            appDb.bookDao.flowAll().mapLatest { books ->
                val keys = arrayListOf<String>()
                books.filterNot { it.isNotShelf }
                    .forEach {
                        keys.add("${it.name}-${it.author}")
                        keys.add(it.name)
                    }
                keys
            }.catch {
                AppLog.put("发现列表界面获取书籍数据失败\n${it.localizedMessage}", it)
            }.collect {
                bookshelf.clear()
                bookshelf.addAll(it)
                upAdapterLiveData.postValue("isInBookshelf")
            }
        }.onError {
            AppLog.put("加载书架数据失败", it)
        }
    }

    /**
     * 初始化数据
     * @param forceReload 是否强制重新加载（跳页时使用）
     */
    fun initData(intent: Intent, forceReload: Boolean = false) {
        execute {
            val sourceUrl = intent.getStringExtra("sourceUrl")
            exploreUrl = intent.getStringExtra("exploreUrl")
            if (bookSource == null && sourceUrl != null) {
                bookSource = appDb.bookSourceDao.getBookSource(sourceUrl)
            }

            // 强制刷新：清空数据 + 重置页码
            if (forceReload) {
                currentPage = 1
                books.clear()
                booksData.postValue(emptyList())
            }

            explore()
        }
    }

    /**
     * 加载下一页
     */
    fun explore() {
        val source = bookSource ?: return
        val url = exploreUrl ?: return

        // 构建带页码的 URL（支持 {page} 占位符）
        val finalUrl = url.replace("{page}", currentPage.toString())

        WebBook.exploreBook(viewModelScope, source, finalUrl, currentPage)
            .timeout(if (BuildConfig.DEBUG) 0L else 60000L)
            .onSuccess(IO) { searchBooks ->
                books.addAll(searchBooks)
                booksData.postValue(books.toList())
                appDb.searchBookDao.insert(*searchBooks.toTypedArray())
                currentPage++  // 成功后页码 +1
            }.onError {
                it.printOnDebug()
                errorLiveData.postValue(it.stackTraceStr)
            }
    }

    /**
     * 跳转到指定页（供 Activity 调用）
     */
    fun jumpToPage(targetPage: Int) {
        if (targetPage <= 0) return
        currentPage = targetPage
        books.clear()
        booksData.postValue(emptyList())
        explore()
    }

    fun isInBookShelf(name: String, author: String): Boolean {
        return if (author.isNotBlank()) {
            bookshelf.contains("$name-$author")
        } else {
            bookshelf.contains(name)
        }
    }
}