package com.machiav3lli.fdroid.ui.viewmodels

import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.machiav3lli.fdroid.database.DatabaseX
import com.machiav3lli.fdroid.database.entity.Extras
import com.machiav3lli.fdroid.database.entity.Installed
import com.machiav3lli.fdroid.database.entity.Product
import com.machiav3lli.fdroid.database.entity.Repository
import com.machiav3lli.fdroid.entity.Request
import com.machiav3lli.fdroid.entity.Section
import com.machiav3lli.fdroid.entity.Source
import com.machiav3lli.fdroid.utility.extension.ManageableLiveData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainNavFragmentViewModelX(
    val db: DatabaseX,
    primarySource: Source,
    secondarySource: Source
) : ViewModel() {
    // TODO add better sort/filter fields

    var updatedFilter: MutableLiveData<Boolean> = MutableLiveData(false)
        private set
    var sections: MutableLiveData<Section> = MutableLiveData(Section.All)
        private set
    var searchQuery: MutableLiveData<String> = MutableLiveData("")
        private set

    fun request(source: Source): Request {
        var mSearchQuery = ""
        var mSections: Section = Section.All
        sections.value?.let { if (source.sections) mSections = it }
        searchQuery.value?.let { mSearchQuery = it }
        return when (source) {
            Source.AVAILABLE -> Request.ProductsAll(
                mSearchQuery,
                mSections
            )
            Source.INSTALLED -> Request.ProductsInstalled(
                mSearchQuery,
                mSections
            )
            Source.UPDATES -> Request.ProductsUpdates(
                mSearchQuery,
                mSections,
            )
            Source.UPDATED -> Request.ProductsUpdated(
                mSearchQuery,
                mSections,
            )
            Source.NEW -> Request.ProductsNew(
                mSearchQuery,
                mSections,
            )
        }
    }

    private var primaryRequest = MediatorLiveData<Request>()
    val primaryProducts = ManageableLiveData<List<Product>>()
    private var secondaryRequest = request(secondarySource)
    val secondaryProducts = MediatorLiveData<List<Product>>()

    val repositories = MediatorLiveData<List<Repository>>()
    val categories = MediatorLiveData<List<String>>()
    val installed = MediatorLiveData<Map<String, Installed>>()

    init {
        primaryProducts.addSource(
            db.productDao.queryLiveList(primaryRequest.value ?: request(primarySource))
        ) {
            primaryProducts.updateValue(it, System.currentTimeMillis())
        }
        secondaryProducts.addSource(
            db.productDao.queryLiveList(secondaryRequest),
            secondaryProducts::setValue
        )
        repositories.addSource(db.repositoryDao.allLive, repositories::setValue)
        categories.addSource(db.categoryDao.allNamesLive, categories::setValue)
        listOf(sections).forEach {
            primaryRequest.addSource(it) {
                val newRequest = request(primarySource)
                if (primaryRequest.value != newRequest)
                    primaryRequest.value = newRequest
            }
        }
        primaryRequest.addSource(updatedFilter) {
            if (it) {
                val newRequest = request(primarySource)
                primaryRequest.value = newRequest
                updatedFilter.postValue(false)
            }
        }
        primaryProducts.addSource(primaryRequest) { request ->
            val updateTime = System.currentTimeMillis()
            viewModelScope.launch {
                withContext(Dispatchers.IO) {
                    primaryProducts.updateValue(
                        db.productDao.queryObject(request),
                        updateTime
                    )
                }
            }
        }
        installed.addSource(db.installedDao.allLive) {
            if (primarySource == Source.INSTALLED && secondarySource == Source.UPDATES) {
                val updateTime = System.currentTimeMillis()
                viewModelScope.launch {
                    withContext(Dispatchers.IO) {
                        secondaryProducts.postValue(db.productDao.queryObject(secondaryRequest))
                        primaryProducts.updateValue(
                            db.productDao.queryObject(
                                primaryRequest.value ?: request(primarySource)
                            ),
                            updateTime
                        )
                    }
                }
            }
            installed.postValue(it.associateBy { it.packageName })
        }
        if (secondaryRequest.updates) secondaryProducts.addSource(db.extrasDao.allLive) {
            viewModelScope.launch {
                withContext(Dispatchers.IO) {
                    secondaryProducts.postValue(db.productDao.queryObject(secondaryRequest))
                }
            }
        }
    }

    fun setFavorite(packageName: String, setBoolean: Boolean) {
        viewModelScope.launch {
            saveFavorite(packageName, setBoolean)
        }
    }

    private suspend fun saveFavorite(packageName: String, setBoolean: Boolean) {
        withContext(Dispatchers.IO) {
            val oldValue = db.extrasDao[packageName]
            if (oldValue != null) db.extrasDao
                .insertReplace(oldValue.copy(favorite = setBoolean))
            else db.extrasDao
                .insertReplace(Extras(packageName, favorite = setBoolean))
        }
    }

    class Factory(
        val db: DatabaseX,
        private val primarySource: Source,
        private val secondarySource: Source
    ) :
        ViewModelProvider.Factory {
        @Suppress("unchecked_cast")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(MainNavFragmentViewModelX::class.java)) {
                return MainNavFragmentViewModelX(db, primarySource, secondarySource) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}
