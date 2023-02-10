package io.gatling.thibaut.azuresdkv2

import com.azure.core.credential.TokenCredential
import com.azure.core.http.policy.HttpLogDetailLevel
import com.azure.core.management.profile.AzureProfile
import com.azure.core.management.{AzureEnvironment, Region}
import com.azure.identity.DefaultAzureCredentialBuilder
import com.azure.resourcemanager.AzureResourceManager
import com.azure.resourcemanager.compute.models.VirtualMachineSizeTypes
import com.azure.resourcemanager.marketplaceordering.MarketplaceOrderingManager
import com.azure.resourcemanager.marketplaceordering.fluent.models.AgreementTermsInner
import com.azure.resourcemanager.marketplaceordering.models.{AgreementTerms, OfferType}
import com.azure.resourcemanager.network.models.{Network, PublicIpAddress}
import com.azure.resourcemanager.resources.models.ResourceGroup
import com.microsoft.azure.batch.BatchClient
import com.microsoft.azure.batch.auth.BatchSharedKeyCredentials
import com.microsoft.azure.batch.protocol.models.{ImageReference, VirtualMachineConfiguration}

import scala.jdk.CollectionConverters._

object Main {
  private val tryName: String = "2"
  private val region = Region.EUROPE_WEST
  private val resourceGroupName = s"my-resource-group-$tryName"
  private val vm_name = "!!!my_-%%t^#&e@@ \\ @st_-vm!!+!"

  val publisher = "gatlingcorp"
  val offer = "openjdk17"
  val plan = "openjdk17"
  val sku = "openjdk17"

  def main(args: Array[String]): Unit = {
//    ScenarioBatch.run(args)
//    ScenarioVm.run(args)

    val client = AzureClient.apply
    val image: AzureClient.Image =
      AzureClient.Image(publisher = "gatlingcorp", offer = "openjdk17", sku = "openjdk17", plan = "openjdk17", region = "westeurope")

    client.runInstance(
      region.toString,
      VirtualMachineSizeTypes.STANDARD_B1S.toString,
      image,
      AzureClient.ResourceGroupName(resourceGroupName).toString,
      AzureClient.VirtualMachineName(vm_name).toString
    )
  }

  def getAzureProfile: AzureProfile =
    new AzureProfile(AzureEnvironment.AZURE)

  def getCredential: TokenCredential =
    new DefaultAzureCredentialBuilder().build()

  def buildResourceManager(
      credential: TokenCredential,
      profile: AzureProfile
  ): AzureResourceManager =
    AzureResourceManager.configure
      .withLogLevel(HttpLogDetailLevel.BASIC)
      .authenticate(credential, profile)
      .withDefaultSubscription

  def buildMarketplaceManager(
      credential: TokenCredential,
      profile: AzureProfile
  ) =
    MarketplaceOrderingManager.authenticate(credential, profile)

  def list_resource_groups(
      azureResourceManager: AzureResourceManager
  ): Unit = {
    val resourceGroups = azureResourceManager.resourceGroups().list()
    for (resourceGroup <- resourceGroups.asScala)
      println(resourceGroup.name())
  }

  object ScenarioVm {
    def run(args: Array[String]): Unit = {
      val credential = getCredential
      val profile: AzureProfile = getAzureProfile

      val resourceManager = buildResourceManager(credential, profile)
      val rg: ResourceGroup =
        createResourceGroup(resourceManager, resourceGroupName)

      //    val ip = createIp(resourceManager, "my-mighty-ip")
      //
      //    val networkName = "my-first-network"
      //    val networkAddressSpace = "10.0.0.0/16"
      //    val networkSubnet = "mySubnet"
      //    val networkSubnetMask = "10.0.0.0/24"
      //    val network = createNetwork(
      //      resourceManager,
      //      rg,
      //      networkName,
      //      networkAddressSpace,
      //      networkSubnet,
      //      networkSubnetMask
      //    )
      //
      //    val interfaceName = "myNIC"
      //    val interface = createInterface(
      //      resourceManager,
      //      rg,
      //      ip,
      //      networkSubnet,
      //      network,
      //      interfaceName
      //    )

      val marketplaceManager = buildMarketplaceManager(credential, profile)
      acceptAgreement(marketplaceManager)

      val image = resourceManager.virtualMachineImages.getImage(
        "westeurope",
        publisher,
        offer,
        sku,
        "latest"
      )

      val vm = resourceManager
        .virtualMachines()
        .define(s"my-vm-$tryName")
        .withRegion(Region.EUROPE_WEST)
        .withExistingResourceGroup(rg)
        .withNewPrimaryNetwork("10.0.0.0/28")
        .withPrimaryPrivateIPAddressDynamic()
        .withNewPrimaryPublicIPAddress(s"my-public-ip-$tryName")
        .withLatestLinuxImage(image.publisherName(), image.offer(), image.sku())
        .withRootUsername("gatling")
        .withRootPassword("gatl1ng!")
        .withSize(VirtualMachineSizeTypes.STANDARD_B1S)
        .withPlan(image.plan())
        .create()

      val result = vm.runShellScript(
        Seq(
          "touch /home/gatling/coucou.txt",
          "touch /home/gatling/coucou2.txt"
        ).asJava,
        List().asJava
      )
      println(result.value().get(0).message())

      // deleteResourceGroup(resourceManager, rg.name())
    }

    private def acceptAgreement(
        marketplaceManager: MarketplaceOrderingManager
    ): AgreementTerms = {
      val agreement = marketplaceManager
        .marketplaceAgreements()
        .get(
          OfferType.VIRTUALMACHINE,
          publisher,
          offer,
          plan
        )

      val acceptedTerms = new AgreementTermsInner()
        .withPublisher(agreement.publisher)
        .withProduct(agreement.product)
        .withPlan(agreement.plan)
        .withLicenseTextLink(agreement.licenseTextLink)
        .withPrivacyPolicyLink(agreement.privacyPolicyLink)
        .withMarketplaceTermsLink(agreement.marketplaceTermsLink)
        .withRetrieveDatetime(agreement.retrieveDatetime)
        .withSignature(agreement.signature)
        .withAccepted(true)

      marketplaceManager
        .marketplaceAgreements()
        .create(OfferType.VIRTUALMACHINE, publisher, offer, plan, acceptedTerms)
    }

    private def createResourceGroup(
        azureResourceManager: AzureResourceManager,
        resourceGroupName: String
    ): ResourceGroup =
      azureResourceManager
        .resourceGroups()
        .define(resourceGroupName)
        .withRegion(region)
        .create()

    private def createInterface(
        azureResourceManager: AzureResourceManager,
        rg: ResourceGroup,
        ip: PublicIpAddress,
        networkSubnet: String,
        network: Network,
        interfaceName: String
    ) =
      azureResourceManager
        .networkInterfaces()
        .define(interfaceName)
        .withRegion(Region.US_EAST)
        .withExistingResourceGroup(rg)
        .withExistingPrimaryNetwork(network)
        .withSubnet(networkSubnet)
        .withPrimaryPrivateIPAddressDynamic()
        .withExistingPrimaryPublicIPAddress(ip)
        .create()

    private def createNetwork(
        azureResourceManager: AzureResourceManager,
        rg: ResourceGroup,
        networkName: String,
        networkAddressSpace: String,
        networkSubnet: String,
        networkSubnetMask: String
    ) =
      azureResourceManager
        .networks()
        .define(networkName)
        .withRegion(Region.EUROPE_WEST)
        .withExistingResourceGroup(rg)
        .withAddressSpace(networkAddressSpace)
        .withSubnet(networkSubnet, networkSubnetMask)
        .create()

    private def createIp(
        azureResourceManager: AzureResourceManager,
        ipname: String
    ) =
      azureResourceManager
        .publicIpAddresses()
        .define(ipname)
        .withRegion(region)
        .withExistingResourceGroup(resourceGroupName)
        .withDynamicIP()
        .create()
  }

  object ScenarioBatch {
    def run(args: Array[String]): Unit = {
      val BATCH_URI = "<batch_uri>"
      // System.getenv("AZURE_BATCH_ENDPOINT")
      val BATCH_ACCOUNT = "<account_name>"
      val BATCH_ACCESS_KEY = "<access_key>"
      val client = BatchClient.open(
        new BatchSharedKeyCredentials(
          BATCH_URI,
          BATCH_ACCOUNT,
          BATCH_ACCESS_KEY
        )
      )
      val vmConfiguration = new VirtualMachineConfiguration()
        .withNodeAgentSKUId("batch.node.ubuntu 20.04")
        .withImageReference(
          new ImageReference()
            .withPublisher(publisher)
            .withSku(sku)
            .withOffer(offer)
        )
      client
        .poolOperations()
        .createPool(
          "my-first-pool",
          VirtualMachineSizeTypes.STANDARD_A1.toString,
          vmConfiguration,
          2
        )
    }
  }

  private def deleteResourceGroup(
      resourceManager: AzureResourceManager,
      resourceGroupName: String
  ): Unit =
    resourceManager
      .resourceGroups()
      .deleteByName(resourceGroupName)
}
