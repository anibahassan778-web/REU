// [REU MonoAnalyzer Decompiler — Assembly-CSharp.dll]
// Provenance: DECOMPILED_RECONSTRUCTED
using System;
using UnityEngine;
using UnityEngine.Advertisements;
using UnityEngine.Events;
using UnityEngine.SceneManagement;
using UnityEngine.UI;

public class ErrorOk : MonoBehaviour
{
    public Button ErrorOkButton;
    public GameObject AdsError;

    // RVA: 0x291d
    private void Start()
    {
        this.ErrorOkButton.onClick.AddListener(new UnityAction(this.OKclicked));
    }

    // RVA: 0x293b
    private void OKclicked()
    {
        this.AdsError.SetActive(false);
    }

    // RVA: 0x2949
    private void Update()
    {
    }

}
