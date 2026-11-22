// [REU MonoAnalyzer Decompiler — Assembly-CSharp.dll]
// Provenance: DECOMPILED_RECONSTRUCTED
using System;
using UnityEngine;
using UnityEngine.Advertisements;

public class AdsInitializer : MonoBehaviour, IUnityAdsInitializationListener
{
    private string _androidGameId;
    private string _iOSGameId;
    private bool _testMode;
    private string _gameId;
    private RewardedAdsButton rewardedAdsButton;

    // RVA: 0x2050
    private void Awake()
    {
        this.InitializeAds();
    }

    // RVA: 0x2058
    public void InitializeAds()
    {
        string tern0;
        if (Application.platform != 8) {
            tern0 = this._androidGameId;
        } else {
            tern0 = this._iOSGameId;
        }
        this._gameId = tern0;
        Advertisement.Initialize(this._gameId, this._testMode, this);
    }

    // RVA: 0x2088
    public void OnInitializationComplete()
    {
        Debug.Log("Unity Ads initialization complete.");
        this.rewardedAdsButton.LoadAd();
    }

    // RVA: 0x209f
    public void OnInitializationFailed(UnityAdsInitializationError error, string message)
    {
        Debug.Log(string.Concat("Unity Ads Initialization Failed: ", error.ToString(), " - ", message));
    }

    // RVA: 0x20c3
    public AdsInitializer()
    {
        this._testMode = true;
    }

}
