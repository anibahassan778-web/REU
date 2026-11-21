package com.reu.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.reu.core.common.AssetKind
import com.reu.core.common.DataProvenance
import com.reu.core.common.ImportSourceType
import com.reu.core.common.JobStatus
import com.reu.core.common.JobType
import com.reu.core.common.UnityScriptingBackend

class ReuTypeConverters {
  @TypeConverter fun fromProvenance(v: DataProvenance): String = v.name
  @TypeConverter fun toProvenance(s: String): DataProvenance = DataProvenance.valueOf(s)

  @TypeConverter fun fromBackend(v: UnityScriptingBackend): String = v.name
  @TypeConverter fun toBackend(s: String): UnityScriptingBackend = UnityScriptingBackend.valueOf(s)

  @TypeConverter fun fromImportSource(v: ImportSourceType): String = v.name
  @TypeConverter fun toImportSource(s: String): ImportSourceType = ImportSourceType.valueOf(s)

  @TypeConverter fun fromJobStatus(v: JobStatus): String = v.name
  @TypeConverter fun toJobStatus(s: String): JobStatus = JobStatus.valueOf(s)

  @TypeConverter fun fromJobType(v: JobType): String = v.name
  @TypeConverter fun toJobType(s: String): JobType = JobType.valueOf(s)

  @TypeConverter fun fromAssetKind(v: AssetKind): String = v.name
  @TypeConverter fun toAssetKind(s: String): AssetKind = AssetKind.valueOf(s)
}

@Database(
  entities = [
    ProjectEntity::class,
    AnalysisJobEntity::class,
    AssetEntryEntity::class,
    CodeSymbolEntity::class,
    DependencyEdgeEntity::class,
    ToolModuleEntity::class,
  ],
  version = 1,
  exportSchema = false,
)
@TypeConverters(ReuTypeConverters::class)
abstract class ReuDatabase : RoomDatabase() {
  abstract fun projectDao(): ProjectDao
  abstract fun jobDao(): JobDao
  abstract fun assetDao(): AssetDao
  abstract fun codeSymbolDao(): CodeSymbolDao
  abstract fun dependencyDao(): DependencyDao
  abstract fun toolModuleDao(): ToolModuleDao

  companion object {
    fun build(context: Context): ReuDatabase {
      return Room.databaseBuilder(context.applicationContext, ReuDatabase::class.java, "reu_workspace.db")
        .fallbackToDestructiveMigration(dropAllTables = true)
        .build()
    }
  }
}
