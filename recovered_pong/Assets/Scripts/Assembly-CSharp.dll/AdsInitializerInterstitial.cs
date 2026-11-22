// [REU MonoAnalyzer Decompiler — Assembly-CSharp.dll]
// Provenance: DECOMPILED_RECONSTRUCTED
using System;
using UnityEngine;
using UnityEngine.Advertisements;

public class AdsInitializerInterstitial : MonoBehaviour, IUnityAdsInitializationListener
{
    private string _androidGameId;
    private string _iOSGameId;
    private bool _testMode;
    private string _gameId;

    // RVA: 0x20d2
    private void Awake()
    {
        this.InitializeAds();
    }

    // RVA: 0x20da
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

    // RVA: 0x210a
    public void OnInitializationComplete()
    {
        Debug.Log("Unity Ads initialization complete.");
    }

    // RVA: 0x2116
    public void OnInitializationFailed(UnityAdsInitializationError error, string message)
    {
        Debug.Log(string.Concat("Unity Ads Initialization Failed: ", error.ToString(), " - ", message));
    }

    // RVA: 0x213a
    public AdsInitializerInterstitial()
    {
        this._testMode = true;
    }

}
