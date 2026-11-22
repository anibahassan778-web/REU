// [REU MonoAnalyzer Decompiler — Assembly-CSharp.dll]
// Provenance: DECOMPILED_RECONSTRUCTED
using System;
using UnityEngine;
using UnityEngine.Advertisements;
using UnityEngine.Events;
using UnityEngine.UI;

public class InterstitialAds : MonoBehaviour, IUnityAdsLoadListener, IUnityAdsShowListener
{
    public Button PlayButton;
    private string _androidAdUnitId;
    private string _iOsAdUnitId;
    private string _adUnitId;

    // RVA: 0x22b6
    private void Update()
    {
        this.PlayButton.onClick.AddListener(new UnityAction(this.ShowAd));
    }

    // RVA: 0x22d4
    private void Awake()
    {
        string tern0;
        if (Application.platform != 8) {
            tern0 = this._androidAdUnitId;
        } else {
            tern0 = this._iOsAdUnitId;
        }
        this._adUnitId = tern0;
    }

    // RVA: 0x22f2
    public void LoadAd()
    {
        Debug.Log(string.Concat("Loading Ad: ", this._adUnitId));
        Advertisement.Load(this._adUnitId, this);
    }

    // RVA: 0x2315
    private void Start()
    {
        this.LoadAd();
    }

    // RVA: 0x231d
    public void ShowAd()
    {
        Debug.Log(string.Concat("Showing Ad: ", this._adUnitId));
        Advertisement.Show(this._adUnitId, this);
        this.LoadAd();
    }

    // RVA: 0x2346
    public void OnUnityAdsAdLoaded(string adUnitId)
    {
    }

    // RVA: 0x2348
    public void OnUnityAdsFailedToLoad(string adUnitId, UnityAdsLoadError error, string message)
    {
        string[] tmp0 = new string[6];
        tmp0[0] = "Error loading Ad Unit: ";
        tmp0[1] = adUnitId;
        tmp0[2] = " - ";
        tmp0[3] = error.ToString();
        tmp0[4] = " - ";
        tmp0[5] = message;
        Debug.Log(string.Concat(tmp0));
    }

    // RVA: 0x2398
    public void OnUnityAdsShowFailure(string adUnitId, UnityAdsShowError error, string message)
    {
        string[] tmp0 = new string[6];
        tmp0[0] = "Error showing Ad Unit ";
        tmp0[1] = adUnitId;
        tmp0[2] = ": ";
        tmp0[3] = error.ToString();
        tmp0[4] = " - ";
        tmp0[5] = message;
        Debug.Log(string.Concat(tmp0));
    }

    // RVA: 0x23e5
    public void OnUnityAdsShowStart(string adUnitId)
    {
    }

    // RVA: 0x23e7
    public void OnUnityAdsShowClick(string adUnitId)
    {
    }

    // RVA: 0x23e9
    public void OnUnityAdsShowComplete(string adUnitId, UnityAdsShowCompletionState showCompletionState)
    {
    }

    // RVA: 0x23eb
    public InterstitialAds()
    {
        this._androidAdUnitId = "Interstitial_Android";
        this._iOsAdUnitId = "Interstitial_iOS";
    }

}
